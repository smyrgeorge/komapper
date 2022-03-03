package org.komapper.spring.r2dbc

import org.komapper.tx.core.TransactionAttribute
import org.komapper.tx.core.TransactionProperty
import org.springframework.transaction.TransactionDefinition

internal class ReactiveTransactionDefinition(
    private val transactionProperty: TransactionProperty,
    private val transactionAttribute: TransactionAttribute
) : TransactionDefinition by TransactionDefinition.withDefaults() {

    override fun getPropagationBehavior(): Int {
        return when (transactionAttribute) {
            TransactionAttribute.REQUIRED -> TransactionDefinition.PROPAGATION_REQUIRED
            TransactionAttribute.REQUIRES_NEW -> TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
    }

    override fun getIsolationLevel(): Int {
        val value = transactionProperty[TransactionProperty.IsolationLevel]
        return if (value != null) {
            when (value) {
                TransactionProperty.IsolationLevel.READ_UNCOMMITTED -> TransactionDefinition.ISOLATION_READ_UNCOMMITTED
                TransactionProperty.IsolationLevel.READ_COMMITTED -> TransactionDefinition.ISOLATION_READ_COMMITTED
                TransactionProperty.IsolationLevel.REPEATABLE_READ -> TransactionDefinition.ISOLATION_REPEATABLE_READ
                TransactionProperty.IsolationLevel.SERIALIZABLE -> TransactionDefinition.ISOLATION_SERIALIZABLE
                else -> error("unknown isolation level: $value")
            }
        } else {
            super.getIsolationLevel()
        }
    }

    override fun isReadOnly(): Boolean {
        return transactionProperty[TransactionProperty.ReadOnly]?.value ?: super.isReadOnly()
    }

    override fun getName(): String? {
        return transactionProperty[TransactionProperty.Name]?.value ?: super.getName()
    }
}
