package com.dirkraft.macroman.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** One logged food item. [day] is an ISO date (yyyy-MM-dd) so a day's log is a simple equality lookup. */
@Entity(tableName = "food")
data class FoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val time: Long,
    val name: String,
    val quantity: String,
    val calories: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    /** Grams of fiber, counted within [carbs] rather than on top of it. */
    @ColumnInfo(defaultValue = "0") val fiber: Double = 0.0,
    /** File name in [PhotoStore] of the picture this was logged from; null once evicted or if there was none. */
    val photo: String? = null,
)

@Entity(tableName = "workout")
data class WorkoutEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val time: Long,
    val exercise: String,
    val sets: Int,
    val reps: Int,
    val weight: Double,
    val unit: String,
    val durationMin: Double,
    val notes: String,
)

/** Water is one running total per day rather than a list of log entries. */
@Entity(tableName = "water")
data class WaterDay(
    @PrimaryKey val day: String,
    val ml: Int,
)

@Dao
interface LogDao {
    @Query("SELECT * FROM food WHERE day = :day ORDER BY time")
    fun food(day: String): Flow<List<FoodEntry>>

    /** Food from a range of days (ISO dates compare correctly as text), the raw material for suggestions. */
    @Query("SELECT * FROM food WHERE day BETWEEN :fromDay AND :toDay ORDER BY time")
    fun foodBetween(fromDay: String, toDay: String): Flow<List<FoodEntry>>

    @Query("SELECT COUNT(*) FROM food WHERE photo = :name")
    suspend fun photoRefs(name: String): Int

    @Query("SELECT DISTINCT photo FROM food WHERE photo IS NOT NULL")
    suspend fun photos(): List<String>

    @Query("UPDATE food SET photo = NULL WHERE photo = :name")
    suspend fun forgetPhoto(name: String)

    @Insert suspend fun insertFood(entries: List<FoodEntry>): List<Long>
    @Update suspend fun updateFood(entry: FoodEntry)
    @Delete suspend fun deleteFood(entry: FoodEntry)

    @Query("SELECT * FROM workout WHERE day = :day ORDER BY time")
    fun workouts(day: String): Flow<List<WorkoutEntry>>

    @Query(
        """SELECT w.* FROM workout w
           JOIN (SELECT MAX(id) AS mid, COUNT(*) AS n FROM workout GROUP BY lower(exercise)) g ON w.id = g.mid
           ORDER BY g.n DESC, w.time DESC LIMIT 15"""
    )
    fun frequentWorkouts(): Flow<List<WorkoutEntry>>

    @Insert suspend fun insertWorkouts(entries: List<WorkoutEntry>): List<Long>
    @Update suspend fun updateWorkout(entry: WorkoutEntry)
    @Delete suspend fun deleteWorkout(entry: WorkoutEntry)

    @Query("SELECT ml FROM water WHERE day = :day")
    fun waterMl(day: String): Flow<Int?>

    @Query("INSERT OR IGNORE INTO water (day, ml) VALUES (:day, 0)")
    suspend fun ensureWaterDay(day: String)

    @Query("UPDATE water SET ml = MAX(0, ml + :deltaMl) WHERE day = :day")
    suspend fun changeWaterMl(day: String, deltaMl: Int)

    @Transaction
    suspend fun addWater(day: String, deltaMl: Int) {
        ensureWaterDay(day)
        changeWaterMl(day, deltaMl)
    }
}

@Database(entities = [FoodEntry::class, WorkoutEntry::class, WaterDay::class], version = 6, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): LogDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE food ADD COLUMN weighedGrams REAL")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `water` (`day` TEXT NOT NULL, `ml` INTEGER NOT NULL, PRIMARY KEY(`day`))")
            }
        }

        /** Drops the scale-weight column; readings already recorded move into the amount text so they aren't lost. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """UPDATE food SET quantity = TRIM(quantity || ' (weighed ' || printf('%g', weighedGrams) || ' g)')
                       WHERE weighedGrams IS NOT NULL"""
                )
                db.execSQL("ALTER TABLE food DROP COLUMN weighedGrams")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE food ADD COLUMN fiber REAL NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE food ADD COLUMN photo TEXT")
            }
        }

        fun open(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "macroman.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
