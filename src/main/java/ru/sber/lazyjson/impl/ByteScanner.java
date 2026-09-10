package ru.sber.lazyjson.impl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Низкоуровневый пропуск JSON-значений по сырым байтам, вперёд и назад. Ничего не декодирует.
 * <p>
 * Контейнеры идут по 8 байт за шаг (SWAR), длинные участки строк — по 32 байта:
 * в словах {@code long} ищутся сразу все интересующие байты. Побайтовый разбор включается
 * на escape-последовательностях и на хвосте.
 * Для контейнера маска кавычек через prefix-xor (назад — suffix-xor) даёт бит «внутри строки» для каждого
 * байта. Маркеры скобок в обоих направлениях суммируются умножением.
 * <p>
 * Назад экранированная кавычка распознаётся по нечётному числу {@code \} непосредственно перед ней.
 */
final class ByteScanner {

    private final byte[] doc;
    private boolean lastStringEscaped;
    private int depth;
    private boolean inString;

    ByteScanner(byte[] doc) {
        this.doc = doc;
    }

    byte[] doc() {
        return doc;
    }

    /** В последней пропущенной строке (вперёд или назад) встречался {@code \}. */
    boolean lastStringEscaped() {
        return lastStringEscaped;
    }

    // --- вперёд -------------------------------------------------------------------------------------

    /** @return позиция сразу за значением, начинающимся в {@code p} */
    int skipValue(int p) {
        byte first = doc[p];
        if (first == '"') {
            return skipString(p);
        }
        if (first == '{' || first == '[') {
            return skipContainer(p);
        }
        return skipScalar(p);
    }

    /** @param p позиция открывающей кавычки; @return позиция сразу за закрывающей */
    int skipString(int p) {
        lastStringEscaped = false;
        return skipStringContent(p + 1);
    }

    private int skipStringContent(int p) {
        while (p <= doc.length - Long.BYTES) {
            long hits = stringHits((long) LONGS.get(doc, p));
            if (hits == 0) {
                p += Long.BYTES;
                while (p <= doc.length - 4 * Long.BYTES) {
                    long hits0 = stringHits((long) LONGS.get(doc, p));
                    long hits1 = stringHits((long) LONGS.get(doc, p + Long.BYTES));
                    long hits2 = stringHits((long) LONGS.get(doc, p + 2 * Long.BYTES));
                    long hits3 = stringHits((long) LONGS.get(doc, p + 3 * Long.BYTES));
                    if ((hits0 | hits1 | hits2 | hits3) == 0) {
                        p += 4 * Long.BYTES;
                        continue;
                    }
                    if ((hits0 | hits1) == 0) {
                        p += 2 * Long.BYTES;
                        hits0 = hits2;
                        hits1 = hits3;
                    }
                    if (hits0 == 0) {
                        p += Long.BYTES;
                        hits0 = hits1;
                    }
                    hits = hits0;
                    break;
                }
                if (hits == 0) {
                    continue;
                }
            }
            p += Long.numberOfTrailingZeros(hits) >>> 3;
            if (doc[p] == '"') {
                return p + 1;
            }
            lastStringEscaped = true;
            p += 2;
        }
        while (p < doc.length) {
            byte b = doc[p];
            if (b == '"') {
                return p + 1;
            }
            if (b == '\\') {
                lastStringEscaped = true;
                p += 2;
            } else {
                p++;
            }
        }
        throw malformed("unterminated string", p);
    }

    /** @param p позиция открывающей скобки; @return позиция сразу за парной закрывающей */
    int skipContainer(int p) {
        int depth = 1;
        boolean inString = false;
        p++;
        while (p <= doc.length - Long.BYTES) {
            long word = (long) LONGS.get(doc, p);
            long quoteBits = matches(word, QUOTES);
            long backslashBits = firstMatch(word, BACKSLASHES);
            if (inString && (quoteBits | backslashBits) == 0) {
                p = skipStringContent(p + Long.BYTES);
                inString = false;
                continue;
            }
            if (backslashBits != 0) {
                this.depth = depth;
                this.inString = inString;
                int found = stepForward(p, p + Long.BYTES);
                if (found >= 0) {
                    return found;
                }
                depth = this.depth;
                inString = this.inString;
                p = ~found;
                continue;
            }
            long inside = prefixXor(quoteBits) ^ (inString ? HIGH_BITS : 0);
            inString = inside < 0;
            long brackets = word & ~CASE_BIT;
            long opens = matches(brackets, OPENS) & ~inside;
            long closes = matches(brackets, CLOSES) & ~inside;
            if (depth - countMarkers(closes) <= 0) {
                for (int i = 0; i < Long.BYTES; i++) {
                    long bit = 0x80L << (i * Long.BYTES);
                    if ((opens & bit) != 0) {
                        depth++;
                    } else if ((closes & bit) != 0 && --depth == 0) {
                        return p + i + 1;
                    }
                }
            } else {
                depth += countMarkers(opens) - countMarkers(closes);
            }
            p += Long.BYTES;
        }
        this.depth = depth;
        this.inString = inString;
        int found = stepForward(p, doc.length);
        if (found >= 0) {
            return found;
        }
        throw malformed("unterminated object or array", doc.length);
    }

    /**
     * Побайтовый проход контейнера по {@code [p, limit)} с состоянием в полях {@link #depth}/{@link #inString}.
     * @return позиция за закрывающей скобкой, если контейнер закончился, иначе {@code ~next} (следующая позиция)
     */
    private int stepForward(int p, int limit) {
        while (p < limit) {
            byte b = doc[p];
            if (inString) {
                if (b == '"') {
                    inString = false;
                } else if (b == '\\') {
                    p++;
                }
            } else if (b == '"') {
                inString = true;
            } else if (b == '{' || b == '[') {
                depth++;
            } else if ((b == '}' || b == ']') && --depth == 0) {
                return p + 1;
            }
            p++;
        }
        return ~p;
    }

    int skipScalar(int p) {
        int start = p;
        while (p < doc.length) {
            byte b = doc[p];
            if (b == ',' || b == '}' || b == ']' || isWhitespace(b)) {
                break;
            }
            p++;
        }
        if (p == start) {
            throw malformed("expected value", p);
        }
        return p;
    }

    /** @return первая непробельная позиция, начиная с {@code p}; конец документа — ошибка */
    int skipWhitespace(int p) {
        while (p < doc.length && isWhitespace(doc[p])) {
            p++;
        }
        if (p >= doc.length) {
            throw malformed("unexpected end of document", p);
        }
        return p;
    }

    // --- назад ---------------------------------------------------------------------------------------

    /** @return позиция первого байта значения, последний байт которого в {@code p} */
    int skipValueBack(int p) {
        byte last = doc[p];
        if (last == '"') {
            return skipStringBack(p);
        }
        if (last == '}' || last == ']') {
            return skipContainerBack(p);
        }
        return skipScalarBack(p);
    }

    /** @param p позиция закрывающей кавычки; @return позиция открывающей */
    int skipStringBack(int p) {
        lastStringEscaped = false;
        return skipStringContentBack(p - 1);
    }

    private int skipStringContentBack(int p) {
        while (p >= Long.BYTES - 1) {
            long hits = exactStringHits((long) LONGS.get(doc, p - Long.BYTES + 1));
            if (hits == 0) {
                p -= Long.BYTES;
                while (p >= 4 * Long.BYTES - 1) {
                    long hits0 = exactStringHits((long) LONGS.get(doc, p - Long.BYTES + 1));
                    long hits1 = exactStringHits((long) LONGS.get(doc, p - 2 * Long.BYTES + 1));
                    long hits2 = exactStringHits((long) LONGS.get(doc, p - 3 * Long.BYTES + 1));
                    long hits3 = exactStringHits((long) LONGS.get(doc, p - 4 * Long.BYTES + 1));
                    if ((hits0 | hits1 | hits2 | hits3) == 0) {
                        p -= 4 * Long.BYTES;
                        continue;
                    }
                    if ((hits0 | hits1) == 0) {
                        p -= 2 * Long.BYTES;
                        hits0 = hits2;
                        hits1 = hits3;
                    }
                    if (hits0 == 0) {
                        p -= Long.BYTES;
                        hits0 = hits1;
                    }
                    hits = hits0;
                    break;
                }
                if (hits == 0) {
                    continue;
                }
            }
            int wordStart = p - Long.BYTES + 1;
            p = wordStart + highestByte(hits);
            if (doc[p] == '\\') {
                lastStringEscaped = true;
                p--;
                continue;
            }
            int backslashes = backslashesBefore(p);
            if ((backslashes & 1) == 0) {
                return p;
            }
            lastStringEscaped = true;
            p -= 1 + backslashes;
        }
        while (p >= 0) {
            byte b = doc[p];
            if (b == '"') {
                int backslashes = backslashesBefore(p);
                if ((backslashes & 1) == 0) {
                    return p;
                }
                lastStringEscaped = true;
                p -= 1 + backslashes;
            } else {
                if (b == '\\') {
                    lastStringEscaped = true;
                }
                p--;
            }
        }
        throw malformed("unterminated string", 0);
    }

    /** @param p позиция закрывающей скобки; @return позиция парной открывающей */
    int skipContainerBack(int p) {
        int depth = 1;
        boolean inString = false;
        p--;
        while (p >= Long.BYTES - 1) {
            int wordStart = p - Long.BYTES + 1;
            long word = (long) LONGS.get(doc, wordStart);
            long quoteBits = matches(word, QUOTES);
            long backslashBits = firstMatch(word, BACKSLASHES);
            if (inString && (quoteBits | backslashBits) == 0) {
                p = skipStringContentBack(wordStart - 1) - 1;
                inString = false;
                continue;
            }
            if (backslashBits != 0 || (wordStart > 0 && doc[wordStart - 1] == '\\')) {
                this.depth = depth;
                this.inString = inString;
                int found = stepBack(p, wordStart);
                if (found >= 0) {
                    return found;
                }
                depth = this.depth;
                inString = this.inString;
                p = -found - 2;
                continue;
            }
            long inside = suffixXor(quoteBits) ^ (inString ? HIGH_BITS : 0);
            inString = (inside & 0x80) != 0;
            long brackets = word & ~CASE_BIT;
            long opens = matches(brackets, OPENS) & ~inside;
            long closes = matches(brackets, CLOSES) & ~inside;
            if (depth - countMarkers(opens) <= 0) {
                for (int i = Long.BYTES - 1; i >= 0; i--) {
                    long bit = 0x80L << (i * Long.BYTES);
                    if ((closes & bit) != 0) {
                        depth++;
                    } else if ((opens & bit) != 0 && --depth == 0) {
                        return wordStart + i;
                    }
                }
            } else {
                depth += countMarkers(closes) - countMarkers(opens);
            }
            p -= Long.BYTES;
        }
        this.depth = depth;
        this.inString = inString;
        int found = stepBack(p, 0);
        if (found >= 0) {
            return found;
        }
        throw malformed("unterminated object or array", 0);
    }

    /**
     * Побайтовый проход контейнера назад по {@code [limit, p]} с состоянием в полях {@link #depth}/{@link #inString}.
     * @return позиция открывающей скобки, иначе {@code -next - 2}; даже next=-1 кодируется отрицательным числом
     */
    private int stepBack(int p, int limit) {
        while (p >= limit) {
            byte b = doc[p];
            if (inString) {
                if (b == '"') {
                    int backslashes = backslashesBefore(p);
                    if ((backslashes & 1) == 0) {
                        inString = false;
                    }
                    p -= backslashes;
                }
            } else if (b == '"') {
                inString = true;
            } else if (b == '}' || b == ']') {
                depth++;
            } else if ((b == '{' || b == '[') && --depth == 0) {
                return p;
            }
            p--;
        }
        return -p - 2;
    }

    private int backslashesBefore(int p) {
        int count = 0;
        while (p - 1 - count >= 0 && doc[p - 1 - count] == '\\') {
            count++;
        }
        return count;
    }

    int skipScalarBack(int p) {
        int end = p;
        while (p >= 0) {
            byte b = doc[p];
            if (b == ',' || b == ':' || b == '{' || b == '[' || isWhitespace(b)) {
                break;
            }
            if (b == '"' || b == '}' || b == ']') {
                throw malformed("unexpected character in scalar", p);
            }
            p--;
        }
        if (p == end) {
            throw malformed("expected value", p);
        }
        return p + 1;
    }

    /** @return последняя непробельная позиция не правее {@code p}; начало документа — ошибка */
    int skipWhitespaceBack(int p) {
        while (p >= 0 && isWhitespace(doc[p])) {
            p--;
        }
        if (p < 0) {
            throw malformed("unexpected start of document", 0);
        }
        return p;
    }

    // --- общее ---------------------------------------------------------------------------------------

    int expect(int p, char c) {
        if (doc[p] != c) {
            throw malformed("expected '" + c + "'", p);
        }
        return p;
    }

    IllegalArgumentException malformed(String what, int at) {
        return new IllegalArgumentException("malformed JSON: " + what + " at offset " + at);
    }

    private static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\n' || b == '\r' || b == '\t';
    }

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long ONES = 0x0101010101010101L;
    private static final long LOW_BITS = 0x7F7F7F7F7F7F7F7FL;
    private static final long HIGH_BITS = 0x8080808080808080L;
    private static final long QUOTES = ONES * '"';
    private static final long BACKSLASHES = ONES * '\\';
    /** {@code '{'}/{@code '['} и {@code '}'}/{@code ']'} отличаются только этим битом. */
    private static final long CASE_BIT = ONES * 0x20;
    private static final long OPENS = ONES * '[';
    private static final long CLOSES = ONES * ']';

    /** Старший бит каждого байта, равного {@code pattern} (байт повторён 8 раз); без ложных срабатываний. */
    private static long matches(long word, long pattern) {
        long x = word ^ pattern;
        return ~(((x & LOW_BITS) + LOW_BITS) | x) & HIGH_BITS;
    }

    /**
     * Кавычки и обратные слэши в слове — дешевле {@link #matches}, но с ложными срабатываниями
     * выше первого настоящего совпадения. Годится там, где нужен только младший бит или проверка на ноль.
     */
    private static long stringHits(long word) {
        return firstMatch(word, QUOTES) | firstMatch(word, BACKSLASHES);
    }

    /** Для старшего совпадения нужна точная маска: stringHits может поставить лишние биты выше настоящего. */
    private static long exactStringHits(long word) {
        return matches(word, QUOTES) | matches(word, BACKSLASHES);
    }

    private static long firstMatch(long word, long pattern) {
        long x = word ^ pattern;
        return (x - ONES) & ~x & HIGH_BITS;
    }

    /** В каждом байте 0 или 0x80: умножение суммирует восемь маркеров в старшем байте (0..8). */
    private static int countMarkers(long bits) {
        return (int) (((bits >>> 7) * ONES) >>> 56);
    }

    /** Индекс старшего байта с установленным старшим битом. */
    private static int highestByte(long highBits) {
        return Long.BYTES - 1 - (Long.numberOfLeadingZeros(highBits) >>> 3);
    }

    /** Старший бит байта i — чётность числа единиц среди старших битов байтов 0..i. */
    private static long prefixXor(long highBits) {
        highBits ^= highBits << 8;
        highBits ^= highBits << 16;
        highBits ^= highBits << 32;
        return highBits;
    }

    /** Старший бит байта i — чётность числа единиц среди старших битов байтов i..7. */
    private static long suffixXor(long highBits) {
        highBits ^= highBits >>> 8;
        highBits ^= highBits >>> 16;
        highBits ^= highBits >>> 32;
        return highBits;
    }
}
