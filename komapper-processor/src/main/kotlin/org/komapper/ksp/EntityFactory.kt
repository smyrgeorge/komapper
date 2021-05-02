package org.komapper.ksp

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.visitor.KSEmptyVisitor

internal class EntityFactory(private val logger: KSPLogger, config: Config, private val entityDef: EntityDef) {

    private val namingStrategy: NamingStrategy = config.namingStrategy
    private val checkCompanionObject: Boolean = config.checkCompanionObject

    fun create(): Entity {
        val allProperties = createAllProperties()
        val idProperties = allProperties.filter { it.kind is PropertyKind.Id }
        val versionProperty: Property? = allProperties.firstOrNull { it.kind is PropertyKind.Version }
        val createdAtProperty: Property? = allProperties.firstOrNull { it.kind is PropertyKind.CreatedAt }
        val updatedAtProperty: Property? = allProperties.firstOrNull { it.kind is PropertyKind.UpdatedAt }
        val ignoredProperties = allProperties.filter { it.kind is PropertyKind.Ignore }
        val properties = allProperties - ignoredProperties
        if (properties.none()) {
            report("Any persistent properties are not found.", entityDef.definitionSource.entityDeclaration)
        }
        return Entity(
            entityDef.definitionSource.entityDeclaration,
            entityDef.table,
            properties.toList(),
            idProperties.toList(),
            versionProperty,
            createdAtProperty,
            updatedAtProperty
        ).also {
            validateEntity(it)
        }
    }

    private fun createAllProperties(): Sequence<Property> {
        val propertyDefMap = entityDef.properties.associateBy { it.declaration.simpleName }
        val (_, entityDeclaration) = entityDef.definitionSource
        val propertyDeclarationMap = entityDeclaration.getDeclaredProperties().associateBy { it.simpleName }
        return entityDeclaration.primaryConstructor?.parameters
            ?.asSequence()
            ?.map { propertyDefMap[it.name!!] to it }
            ?.map { (propertyDef, parameter) ->
                val declaration = propertyDeclarationMap[parameter.name]
                    ?: report("The corresponding property declaration is not found.", parameter)
                val column = getColumn(propertyDef, parameter)
                val type = parameter.type.resolve()
                val typeName = (type.declaration.qualifiedName ?: type.declaration.simpleName).asString()
                val literalTag = resolveLiteralTag(typeName)
                val valueClass = createValueClass(type)
                val nullability = type.nullability
                val kind = propertyDef?.kind
                Property(parameter, declaration, column, typeName, literalTag, valueClass, nullability, kind).also {
                    validateProperty(it)
                }
            }?.also {
                validateAllProperties(it)
            } ?: emptySequence()
    }

    private fun createValueClass(type: KSType): ValueClass? {
        return type.declaration.accept(
            object : KSEmptyVisitor<Unit, ValueClass?>() {
                override fun defaultHandler(node: KSNode, data: Unit): ValueClass? {
                    return null
                }

                override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit): ValueClass? {
                    val constructor = classDeclaration.primaryConstructor
                    val isPublic = constructor?.isPublic() ?: false
                    val parameter = constructor?.parameters?.firstOrNull()
                    val declaration = classDeclaration.getDeclaredProperties().firstOrNull()
                    return if (classDeclaration.isValueClass() && isPublic && parameter != null && declaration != null) {
                        val interiorType = parameter.type.resolve()
                        val typeName =
                            (interiorType.declaration.qualifiedName ?: interiorType.declaration.simpleName).asString()
                        val literalTag = resolveLiteralTag(typeName)
                        val nullability = interiorType.nullability
                        val property = ValueClassProperty(parameter, declaration, typeName, literalTag, nullability)
                        return ValueClass(classDeclaration, property)
                    } else {
                        null
                    }
                }
            },
            Unit
        )
    }

    private fun resolveLiteralTag(typeName: String): String {
        return when (typeName) {
            "kotlin.Long" -> "L"
            "kotlin.UInt" -> "u"
            else -> ""
        }
    }

    private fun getColumn(propertyDef: PropertyDef?, parameter: KSValueParameter): Column {
        return if (propertyDef == null) {
            val name = parameter.name?.asString() ?: report("The name is not found.", parameter)
            Column(namingStrategy.apply(name), false)
        } else {
            propertyDef.column
        }
    }

    private fun validateProperty(property: Property) {
        if (property.isPrivate()) {
            report("The property must not be private.", property.parameter)
        }
        if (property.valueClass != null) {
            validateValueClassProperty(property, property.valueClass.property)
        }
        when (val kind = property.kind) {
            is PropertyKind.Id -> validateIdProperty(property, kind.idKind)
            is PropertyKind.Version -> validateVersionProperty(property)
            is PropertyKind.CreatedAt -> validateTimestampProperty(property, "@KmCreatedAt")
            is PropertyKind.UpdatedAt -> validateTimestampProperty(property, "@KmUpdatedAt")
            is PropertyKind.Ignore -> validateIgnoreProperty(property)
            else -> Unit
        }
    }

    private fun validateValueClassProperty(property: Property, valueClassProperty: ValueClassProperty) {
        if (valueClassProperty.isPrivate()) {
            report(
                "The value class's own property '$valueClassProperty' must not be private.",
                property.parameter
            )
        }
        if (valueClassProperty.nullability == Nullability.NULLABLE) {
            report(
                "The value class's own property '$valueClassProperty' must not be nullable.",
                property.parameter
            )
        }
    }

    private fun validateIdProperty(property: Property, idKind: IdKind?) {
        if (idKind == null) return
        fun validate(annotationName: String) {
            when (property.typeName) {
                "kotlin.Int", "kotlin.Long", "kotlin.UInt" -> Unit
                else -> {
                    if (property.valueClass == null) {
                        report(
                            "The type of $annotationName annotated property must be either Int, Long, UInt or value class.",
                            property.parameter
                        )
                    } else {
                        when (property.valueClass.property.typeName) {
                            "kotlin.Int", "kotlin.Long", "kotlin.UInt" -> Unit
                            else -> report(
                                "When the type of $annotationName annotated property is value class, the type of the value class's own property must be either Int, Long or UInt.",
                                property.parameter
                            )
                        }
                    }
                }
            }
        }
        when (idKind) {
            is IdKind.AutoIncrement -> validate("@KmAutoIncrement")
            is IdKind.Sequence -> validate("@KmSequence")
        }
    }

    private fun validateVersionProperty(property: Property) {
        when (property.typeName) {
            "kotlin.Int", "kotlin.Long", "kotlin.UInt" -> Unit
            else -> {
                if (property.valueClass == null) {
                    report(
                        "The type of @KmVersion annotated property must be either Int, Long, UInt or value class.",
                        property.parameter
                    )
                } else {
                    when (property.valueClass.property.typeName) {
                        "kotlin.Int", "kotlin.Long", "kotlin.UInt" -> Unit
                        else -> report(
                            "When the type of @KmVersion annotated property is value class, the type of the value class's own property must be either Int, Long or UInt.",
                            property.parameter
                        )
                    }
                }
            }
        }
    }

    private fun validateTimestampProperty(property: Property, annotationName: String) {
        when (property.typeName) {
            "java.time.LocalDateTime",
            "java.time.OffsetDateTime" -> Unit
            else -> {
                if (property.valueClass == null) {
                    report(
                        "The type of $annotationName annotated property must be either LocalDateTime or OffsetDateTime.",
                        property.parameter
                    )
                } else {
                    when (property.valueClass.property.typeName) {
                        "java.time.LocalDateTime",
                        "java.time.OffsetDateTime" -> Unit
                        else -> report(
                            "When the type of $annotationName annotated property is value class, the type of the value class's own property must be either LocalDateTime or OffsetDateTime.",
                            property.parameter
                        )
                    }
                }
            }
        }
    }

    private fun validateIgnoreProperty(property: Property) {
        if (!property.parameter.hasDefault) {
            report("The ignored property must have a default value.", property.parameter)
        }
    }

    private fun validateAllProperties(properties: Sequence<Property>) {
        val propertyDefMap = entityDef.properties.associateBy { it.declaration.simpleName }
        val propertyMap = properties.associateBy { it.declaration.simpleName }
        for ((key, value) in propertyDefMap) {
            propertyMap[key]
                ?: report("The same name property is not found in the entity.", value.parameter)
        }
        val idKinds = properties.mapNotNull {
            when (it.kind) {
                is PropertyKind.Id -> it.kind.idKind
                else -> null
            }
        }
        if (idKinds.count() > 1) {
            report(
                "@KmAutoIncrement and @KmSequence cannot coexist in a single class.",
                entityDef.definitionSource.entityDeclaration
            )
        }
    }

    private fun validateEntity(entity: Entity) {
        if (entity.declaration.simpleName.asString().startsWith("__")) {
            report("The class name cannot start with '__'.", entity.declaration)
        }
        for (p in entity.properties) {
            val name = (p.declaration.simpleName).asString()
            if (name.startsWith("__")) {
                report("The property name cannot start with '__'.", p.parameter)
            }
        }
        if (entity.idProperties.isEmpty()) {
            report("The entity class must have at least one id property.", entity.declaration)
        }
        if (checkCompanionObject && !entity.declaration.hasCompanionObject()) {
            logger.warn(
                "We recommend to define a companion object in the entity class. " +
                    "To suppress this warning message, configure as follows in your build file: " +
                    "'ksp { arg(\"komapper.checkCompanionObject\", \"false\") }'",
                entity.declaration
            )
        }
    }
}
