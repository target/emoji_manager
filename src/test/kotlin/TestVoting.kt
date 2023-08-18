
import com.target.slack.isWeekend
import com.target.slack.plusBusinessDays
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TestVoting {

    private val calendarHolidays: List<LocalDate> = listOf(
        LocalDate.of(2021, 7, 5), // Independence Day (US)
        LocalDate.of(2021, 7, 14), // Made Up Holiday (None)
        LocalDate.of(2021, 7, 29), // Made Up Holiday (None)
        LocalDate.of(2021, 11, 25), // Thanksgiving (US)
    )

    @Test
    fun testWeekend() {
        val weekday = LocalDateTime.of(2021, 1, 4, 0, 0)
        val weekend = LocalDateTime.of(2021, 1, 2, 0, 0)

        assert(weekend.isWeekend())
        assert(!weekday.isWeekend())
    }

    @Test
    fun countBusinessDays() {
        // Has no holidays/weekends
        val start = LocalDateTime.of(2021, 7, 19, 0, 0)
        val end = start.plusBusinessDays(3, calendarHolidays)
        val expected = LocalDateTime.of(2021, 7, 22, 0, 0)

        assert(end == expected)
    }

    @Test
    fun countBusinessDaysWithWeekend() {
        // Has weekend
        val start = LocalDateTime.of(2021, 7, 15, 0, 0)
        val end = start.plusBusinessDays(3, calendarHolidays)
        val expected = LocalDateTime.of(2021, 7, 20, 0, 0)

        assert(end == expected)
    }

    @Test
    fun countBusinessDaysWithHoliday() {
        // Has holiday and weekend in it
        val start = LocalDateTime.of(2021, 7, 12, 0, 0)
        val end = start.plusBusinessDays(3, calendarHolidays)
        val expected = LocalDateTime.of(2021, 7, 16, 0, 0)

        assert(end == expected)
    }

    @Test
    fun countBusinessDaysWithHolidayAndWeekend() {
        val start = LocalDateTime.of(2021, 7, 1, 0, 0)
        val end = start.plusBusinessDays(3, calendarHolidays)
        val expected = LocalDateTime.of(2021, 7, 7, 0, 0)

        assert(end.equals(expected))
    }
}
