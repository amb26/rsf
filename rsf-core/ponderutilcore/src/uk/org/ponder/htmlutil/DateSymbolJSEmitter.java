/*
 * Created on 26 Oct 2006
 */
package uk.org.ponder.htmlutil;

import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

import uk.org.ponder.arrayutil.ArrayUtil;
import uk.org.ponder.localeutil.LocaleHolder;
import uk.org.ponder.stringutil.CharWrap;

/**
 * Emits a selection of Date symbols and formats for a particular Locale,
 * suitable for inclusion as part of a Javascript &lt;script&gt; block.
 * 
 * @author Antranig Basman (antranig@caret.cam.ac.uk)
 */

public class DateSymbolJSEmitter extends LocaleHolder {
  private String prefix = "PUC_";

  public void setArrayNamePrefix(String prefix) {
    this.prefix = prefix;
  }

  public String emitDateSymbols() {
    Locale locale = getLocale();
    CharWrap togo = new CharWrap();
    togo.append(HTMLConstants.JS_BLOCK_START);
    
    Calendar defcal = Calendar.getInstance(locale);
    DateFormatSymbols formatsymbols = new DateFormatSymbols(locale);
    // note that Java numbers months starting at 0!
    int cmonths = defcal.getActualMaximum(Calendar.MONTH) + 1;

    String[] monthlong = formatsymbols.getMonths();
    monthlong = (String[]) ArrayUtil.trim(monthlong, cmonths);
    togo
        .append(HTMLUtil.emitJavascriptArray(prefix + "MONTHS_LONG", monthlong));

    String[] monthshort = formatsymbols.getShortMonths();
    monthshort = (String[]) ArrayUtil.trim(monthshort, cmonths);
    togo.append(HTMLUtil.emitJavascriptArray(prefix + "MONTHS_SHORT",
        monthshort));

    String[] weeklong = formatsymbols.getWeekdays();
    // note that Java numbers weekdays starting at 1!
    weeklong = (String[]) ArrayUtil.subArray(weeklong, 1, 8);
    togo.append(HTMLUtil
        .emitJavascriptArray(prefix + "WEEKDAYS_LONG", weeklong));

    String[] weekmed = formatsymbols.getShortWeekdays();
    weekmed = (String[]) ArrayUtil.subArray(weekmed, 1, 8);
    togo.append(HTMLUtil.emitJavascriptArray(prefix + "WEEKDAYS_MEDIUM",
        weekmed));

    String[] weekshort = shortenWeekdays(weekmed);
    togo.append(HTMLUtil.emitJavascriptArray(prefix + "WEEKDAYS_SHORT",
        weekshort));

    String[] weekonechar = oneCharWeekdays(weekmed);
    togo.append(HTMLUtil.emitJavascriptArray(prefix + "WEEKDAYS_1CHAR",
        weekonechar));

    int firstday = defcal.getFirstDayOfWeek() - 1;
    togo.append(HTMLUtil.emitJavascriptVar(prefix + "FIRST_DAY_OF_WEEK",
        Integer.toString(firstday)));

    // These illegal casts are required to access the pattern string
    SimpleDateFormat dateformat = (SimpleDateFormat) DateFormat
        .getDateInstance(DateFormat.SHORT, locale);
    togo.append(HTMLUtil.emitJavascriptVar(prefix + "DATE_FORMAT", dateformat
        .toLocalizedPattern()));

    SimpleDateFormat datetimeformat = (SimpleDateFormat) DateFormat
        .getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale);
    togo.append(HTMLUtil.emitJavascriptVar(prefix + "DATETIME_FORMAT",
        datetimeformat.toLocalizedPattern()));

    SimpleDateFormat timeformat = (SimpleDateFormat) DateFormat
        .getTimeInstance(DateFormat.SHORT, locale);
    togo.append(HTMLUtil.emitJavascriptVar(prefix + "TIME_FORMAT", timeformat
        .toLocalizedPattern()));

    togo.append(HTMLConstants.JS_BLOCK_END);
    return togo.toString();
  }

  public static String shorten(String s, int prefix, int length) {
    if (prefix != 0) {
      s = s.substring(prefix);
    }
    return s.length() < length ? s
        : s.substring(0, length);

  }

  // Deal with oriental-style weekday names by removing any common prefix
  public static int getCommonPrefix(String[] names) {
    if (names.length < 2)
      return 0;
    else {
      int index = 0;
      while (true) {
        if (index >= names[0].length() || index >= names[1].length())
          break;
        if (names[0].charAt(index) != names[1].charAt(index))
          break;
        ++index;
      }
      return index;
    }
  }

  String[] shortenWeekdays(String[] weekmed) {
    String[] togo = new String[weekmed.length];
    int prefix = getCommonPrefix(weekmed);
    for (int i = 0; i < weekmed.length; ++i) {
      togo[i] = shorten(weekmed[i], prefix, 2);
    }
    return togo;
  }

  String[] oneCharWeekdays(String[] weekmed) {
    String[] togo = new String[weekmed.length];
    int prefix = getCommonPrefix(weekmed);
    for (int i = 0; i < weekmed.length; ++i) {
      togo[i] = shorten(weekmed[i], prefix, 1);
    }
    return togo;
  }
}
