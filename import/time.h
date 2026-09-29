/* <time.h> -- C99 7.23. time()/clock() are implemented in
 * StandardRuntime.java on the JVM backend (a real wall clock; see that
 * file's own doc comment on clock()'s one unavoidable approximation),
 * and by the real libc on x86. Everything else here -- calendar
 * conversions, formatting -- is plain portable C needing nothing
 * host-specific, written directly in this header ("static", so two
 * different .c files both including it don't collide -- see assert.h's
 * own doc comment for exactly why).
 *
 * No timezone/locale database exists in this compiler at all (see
 * README's "still not provided" list), so localtime() behaves exactly
 * like gmtime() -- this always reports UTC, never a real local
 * timezone -- and strftime()'s "%Z"/"%z" always render as "UTC"/"+0000". */
#ifndef _CFLAT_TIME_H
#define _CFLAT_TIME_H

#include "stddef.h"  /* for size_t, NULL */
#include "stdio.h"   /* for sprintf(), used by asctime()/strftime() below */

typedef long time_t;
typedef long clock_t;

/* Matches clock()'s own 1-microsecond unit in StandardRuntime.java, and
 * glibc's own value on x86 -- a real program dividing by this to get
 * seconds gets the right answer on either backend. */
#define CLOCKS_PER_SEC 1000000L

struct tm {
    int tm_sec;    /* seconds after the minute, 0-60 (61 for a leap second -- never produced here) */
    int tm_min;    /* minutes after the hour, 0-59 */
    int tm_hour;   /* hours since midnight, 0-23 */
    int tm_mday;   /* day of the month, 1-31 */
    int tm_mon;    /* months since January, 0-11 */
    int tm_year;   /* years since 1900 */
    int tm_wday;   /* days since Sunday, 0-6 */
    int tm_yday;   /* days since January 1, 0-365 */
    int tm_isdst;  /* always 0 here -- no timezone/DST database exists */
};

extern time_t time(time_t* tp);
extern clock_t clock(void);

static double difftime(time_t time1, time_t time0) {
    return (double)(time1 - time0);
}

/* Howard Hinnant's "days from/to civil" algorithms
 * (http://howardhinnant.github.io/date_algorithms.html, released into
 * the public domain by its author) -- exact proleptic-Gregorian
 * civil-calendar <-> days-since-1970-01-01 conversion for any year, in
 * plain integer arithmetic, with no calendar table or libc call needed. */
static long __cflat_days_from_civil(long y, int m, int d) {
    y -= (m <= 2);
    long era = (y >= 0 ? y : y - 399) / 400;
    long yoe = y - era * 400;
    long mp = (m + (m > 2 ? -3 : 9));
    long doy = (153 * mp + 2) / 5 + d - 1;
    long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + doe - 719468;
}

static void __cflat_civil_from_days(long z, int* y, int* m, int* d) {
    z += 719468;
    long era = (z >= 0 ? z : z - 146096) / 146097;
    long doe = z - era * 146097;
    long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    long yy = yoe + era * 400;
    long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    long mp = (5 * doy + 2) / 153;
    *d = (int)(doy - (153 * mp + 2) / 5 + 1);
    *m = (int)(mp < 10 ? mp + 3 : mp - 9);
    *y = (int)(yy + (*m <= 2 ? 1 : 0));
}

/* Real gmtime()/localtime() return a pointer into one shared, statically
 * allocated struct tm that the next call overwrites -- exactly like a
 * real (thread-unsafe, but standard-mandated) libc implementation. */
static struct tm __cflat_tm_buf;

static struct tm* gmtime(time_t* timer) {
    long t = *timer;
    long days = t / 86400;
    long rem = t % 86400;
    if (rem < 0) {
        rem += 86400;
        days -= 1;
    }
    int y, mo, d;
    __cflat_civil_from_days(days, &y, &mo, &d);
    __cflat_tm_buf.tm_year = y - 1900;
    __cflat_tm_buf.tm_mon = mo - 1;
    __cflat_tm_buf.tm_mday = d;
    __cflat_tm_buf.tm_hour = (int)(rem / 3600);
    __cflat_tm_buf.tm_min = (int)((rem % 3600) / 60);
    __cflat_tm_buf.tm_sec = (int)(rem % 60);
    /* 1970-01-01 (days == 0) was a Thursday (POSIX day-of-week 4). */
    long wday = ((days % 7) + 7 + 4) % 7;
    __cflat_tm_buf.tm_wday = (int)wday;
    __cflat_tm_buf.tm_yday = (int)(days - __cflat_days_from_civil(y, 1, 1));
    __cflat_tm_buf.tm_isdst = 0;
    return &__cflat_tm_buf;
}

static struct tm* localtime(time_t* timer) {
    return gmtime(timer);
}

static time_t mktime(struct tm* tmv) {
    long days = __cflat_days_from_civil(tmv->tm_year + 1900, tmv->tm_mon + 1, tmv->tm_mday);
    time_t secs = days * 86400L + tmv->tm_hour * 3600L + tmv->tm_min * 60L + tmv->tm_sec;
    /* Normalize every field (including tm_wday/tm_yday) the same way a
     * real mktime() does, by round-tripping through gmtime() -- member
     * by member, since a whole "*tmv = ...;" struct assignment is JVM-
     * backend only (see the x86 backend's own note on it in the main
     * README's "struct/union by value" section). */
    gmtime(&secs);
    tmv->tm_sec = __cflat_tm_buf.tm_sec;
    tmv->tm_min = __cflat_tm_buf.tm_min;
    tmv->tm_hour = __cflat_tm_buf.tm_hour;
    tmv->tm_mday = __cflat_tm_buf.tm_mday;
    tmv->tm_mon = __cflat_tm_buf.tm_mon;
    tmv->tm_year = __cflat_tm_buf.tm_year;
    tmv->tm_wday = __cflat_tm_buf.tm_wday;
    tmv->tm_yday = __cflat_tm_buf.tm_yday;
    tmv->tm_isdst = __cflat_tm_buf.tm_isdst;
    return secs;
}

static char* __cflat_wday_name[7] = {
    "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"
};
static char* __cflat_wday_full[7] = {
    "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
};
static char* __cflat_mon_name[12] = {
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
};
static char* __cflat_mon_full[12] = {
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
};

static char __cflat_asctime_buf[26];

/* "Www Mon dd hh:mm:ss yyyy\n" -- always exactly 25 characters plus the
 * trailing '\0', same as a real asctime(); tm_mday uses a space-padded
 * (not zero-padded) two-digit field, same real-world quirk. */
static char* asctime(struct tm* tmv) {
    sprintf(__cflat_asctime_buf, "%s %s %2d %02d:%02d:%02d %d\n",
            __cflat_wday_name[tmv->tm_wday], __cflat_mon_name[tmv->tm_mon],
            tmv->tm_mday, tmv->tm_hour, tmv->tm_min, tmv->tm_sec,
            tmv->tm_year + 1900);
    return __cflat_asctime_buf;
}

static char* ctime(time_t* timer) {
    return asctime(gmtime(timer));
}

/* A useful, commonly-needed subset of real strftime()'s format
 * specifiers -- not the full POSIX set (no locale-dependent "%c"/"%x"/
 * "%X", no ISO week-number specifiers, ...), each handled by formatting
 * into a small local buffer and appending it. Returns the number of
 * characters written (excluding the terminating '\0'), or 0 if the
 * result (plus terminator) wouldn't fit in "max" -- same contract as
 * the real function. */
static size_t strftime(char* s, size_t max, char* format, struct tm* tmv) {
    char piece[64];
    size_t len = 0;
    char* p = format;
    while (*p != '\0') {
        if (*p != '%') {
            if (len + 1 >= max) return 0;
            s[len++] = *p;
            p++;
            continue;
        }
        p++;
        switch (*p) {
        case 'Y': sprintf(piece, "%d", tmv->tm_year + 1900); break;
        case 'y': sprintf(piece, "%02d", (tmv->tm_year + 1900) % 100); break;
        case 'm': sprintf(piece, "%02d", tmv->tm_mon + 1); break;
        case 'd': sprintf(piece, "%02d", tmv->tm_mday); break;
        case 'e': sprintf(piece, "%2d", tmv->tm_mday); break;
        case 'H': sprintf(piece, "%02d", tmv->tm_hour); break;
        case 'I': {
            int h12 = tmv->tm_hour % 12;
            if (h12 == 0) h12 = 12;
            sprintf(piece, "%02d", h12);
            break;
        }
        case 'M': sprintf(piece, "%02d", tmv->tm_min); break;
        case 'S': sprintf(piece, "%02d", tmv->tm_sec); break;
        case 'p': sprintf(piece, "%s", (tmv->tm_hour < 12) ? "AM" : "PM"); break;
        case 'a': sprintf(piece, "%s", __cflat_wday_name[tmv->tm_wday]); break;
        case 'A': sprintf(piece, "%s", __cflat_wday_full[tmv->tm_wday]); break;
        case 'b': case 'h': sprintf(piece, "%s", __cflat_mon_name[tmv->tm_mon]); break;
        case 'B': sprintf(piece, "%s", __cflat_mon_full[tmv->tm_mon]); break;
        case 'j': sprintf(piece, "%03d", tmv->tm_yday + 1); break;
        case 'w': sprintf(piece, "%d", tmv->tm_wday); break;
        case 'n': sprintf(piece, "\n"); break;
        case 't': sprintf(piece, "\t"); break;
        case 'Z': sprintf(piece, "UTC"); break;
        case 'z': sprintf(piece, "+0000"); break;
        case '%': sprintf(piece, "%%"); break;
        case '\0':
            /* A trailing bare '%' with nothing after it: emit it
             * literally rather than reading past the format string. */
            sprintf(piece, "%%");
            p--;
            break;
        default:
            piece[0] = '%';
            piece[1] = *p;
            piece[2] = '\0';
            break;
        }
        {
            char* q = piece;
            while (*q != '\0') {
                if (len + 1 >= max) return 0;
                s[len++] = *q;
                q++;
            }
        }
        p++;
    }
    s[len] = '\0';
    return len;
}

#endif
