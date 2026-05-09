package cy.txtracker.data

import androidx.room.TypeConverter
import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant

class Converters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilliseconds()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::fromEpochMilliseconds)

    @TypeConverter
    fun timeBucketToString(value: TimeBucket?): String? = value?.name

    @TypeConverter
    fun stringToTimeBucket(value: String?): TimeBucket? = value?.let(TimeBucket::valueOf)

    @TypeConverter
    fun directionToString(value: Direction?): String? = value?.name

    @TypeConverter
    fun stringToDirection(value: String?): Direction? = value?.let(Direction::valueOf)
}
