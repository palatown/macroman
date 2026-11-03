package com.dirkraft.macroman.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
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

@Dao
interface LogDao {
    @Query("SELECT * FROM food WHERE day = :day ORDER BY time")
    fun food(day: String): Flow<List<FoodEntry>>

    /** Latest entry of each distinct food, most frequently logged first: the one-tap re-log row. */
    @Query(
        """SELECT f.* FROM food f
           JOIN (SELECT MAX(id) AS mid, COUNT(*) AS n FROM food GROUP BY lower(name)) g ON f.id = g.mid
           ORDER BY g.n DESC, f.time DESC LIMIT 15"""
    )
    fun frequentFood(): Flow<List<FoodEntry>>

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
}

@Database(entities = [FoodEntry::class, WorkoutEntry::class], version = 1)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): LogDao

    companion object {
        fun open(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "macroman.db").build()
    }
}
