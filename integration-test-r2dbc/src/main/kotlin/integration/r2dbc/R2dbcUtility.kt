package integration.r2dbc

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.komapper.r2dbc.R2dbcDatabase
import org.komapper.tx.core.CoroutineTransactionOperator

fun <T> inTransaction(db: R2dbcDatabase, block: suspend (CoroutineTransactionOperator) -> T) {
    runBlockingWithTimeout {
        db.withTransaction {
            it.setRollbackOnly()
            block(it)
        }
    }
}

fun <T> runBlockingWithTimeout(timeMillis: Long = 30000L, block: suspend () -> T) {
    runBlocking {
        withTimeout(timeMillis) {
            block()
        }
    }
}
