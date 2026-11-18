package uz.meningmoliyam.app

import android.content.Context
import androidx.room.*

@Entity(tableName = "transactions")
data class FinanceEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String, // INCOME, EXPENSE, DEBT_OWED, DEBT_GIVEN, DEBT_PAYMENT
    val title: String,
    val amount: Long,
    val date: Long = System.currentTimeMillis(),
    val note: String = ""
)

@Dao
interface FinanceDao {
    @Query("SELECT * FROM transactions ORDER BY date DESC")
    suspend fun all(): List<FinanceEntry>

    @Insert
    suspend fun insert(entry: FinanceEntry)

    @Delete
    suspend fun delete(entry: FinanceEntry)
}

@Database(entities = [FinanceEntry::class], version = 1, exportSchema = false)
abstract class FinanceDb : RoomDatabase() {
    abstract fun dao(): FinanceDao
    companion object {
        @Volatile private var INSTANCE: FinanceDb? = null
        fun get(context: Context): FinanceDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext, FinanceDb::class.java, "mening_moliyam.db"
            ).build().also { INSTANCE = it }
        }
    }
}
