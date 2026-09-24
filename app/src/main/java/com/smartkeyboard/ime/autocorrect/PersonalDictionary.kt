package com.smartkeyboard.ime.autocorrect

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.Locale

/**
 * Layer 3 — adaptive personal dictionary (Room).
 * Stores custom words / slang the user accepted or undid corrections for.
 */
@Entity(tableName = "personal_words")
data class PersonalWord(
    @PrimaryKey val word: String,
    val frequency: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface PersonalWordDao {
    @Query("SELECT * FROM personal_words ORDER BY frequency DESC, updatedAt DESC LIMIT :limit")
    suspend fun topWords(limit: Int = 500): List<PersonalWord>

    @Query("SELECT * FROM personal_words WHERE word LIKE :prefix || '%' ORDER BY frequency DESC LIMIT :limit")
    suspend fun prefix(prefix: String, limit: Int = 20): List<PersonalWord>

    @Query("SELECT * FROM personal_words WHERE word = :word LIMIT 1")
    suspend fun find(word: String): PersonalWord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(word: PersonalWord)

    @Query("DELETE FROM personal_words WHERE word = :word")
    suspend fun delete(word: String)

    @Query("SELECT COUNT(*) FROM personal_words")
    suspend fun count(): Int
}

@Database(entities = [PersonalWord::class], version = 1, exportSchema = false)
abstract class PersonalDictionaryDb : RoomDatabase() {
    abstract fun dao(): PersonalWordDao
}

class PersonalDictionary(context: Context) {

    private val db = Room.databaseBuilder(
        context.applicationContext,
        PersonalDictionaryDb::class.java,
        "smart_kb_personal_dict"
    ).fallbackToDestructiveMigration().build()

    private val dao = db.dao()

    /** In-memory cache for fast prefix / lookup access. */
    @Volatile
    private var cache = emptyMap<String, Int>()

    suspend fun warmUp() {
        cache = dao.topWords(800).associate { it.word to it.frequency }
    }

    fun cachedWords(): Map<String, Int> = cache

    suspend fun learn(word: String, boost: Int = 4) {
        val clean = word.trim().lowercase(Locale.getDefault())
        if (clean.length < 2 || clean.any { !it.isLetter() && it != '-' }) return
        val existing = dao.find(clean)
        val next = (existing?.frequency ?: 0) + boost
        dao.upsert(PersonalWord(clean, next))
        cache = cache + (clean to next)
    }

    suspend fun contains(word: String): Boolean {
        val clean = word.lowercase(Locale.getDefault())
        if (cache.containsKey(clean)) return true
        return dao.find(clean) != null
    }

    suspend fun prefixMatches(prefix: String, limit: Int = 8): List<Pair<String, Int>> {
        val lower = prefix.lowercase(Locale.getDefault())
        // Prefer cache for speed
        val fromCache = cache.entries
            .filter { it.key.startsWith(lower) }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
        if (fromCache.size >= limit) return fromCache
        return dao.prefix(lower, limit).map { it.word to it.frequency }
    }
}
