package com.example.polymarket.core;

/**
 * 从 WebSocket JSON 文本中快速提取 {@code event_type}，避免在 OkHttp 回调线程中构造完整 JSON 树。
 *
 * <p>扫描器无共享状态，可并发调用。它只识别协议所需的简单字符串字段；格式异常、超长类型或空数组统一返回
 * {@code unknown}。
 */
public final class EventTypeScanner {
    private static final String KEY = "\"event_type\"";
    private static final int MAX_EVENT_TYPE_LENGTH = 63;

    private EventTypeScanner() {}

    public static String scan(String text) {
        if (text == null) {
            return "unknown";
        }
        int first = firstNonWhitespace(text, 0, text.length());
        if (first < 0) {
            return "unknown";
        }
        if (text.charAt(first) != '[') {
            return scanRange(text, first, text.length());
        }
        return scanTopLevelArray(text, first + 1);
    }

    private static String scanTopLevelArray(String text, int start) {
        String commonType = null;
        int elementStart = start;
        int objectDepth = 0;
        int arrayDepth = 1;
        boolean inString = false;
        boolean escaped = false;
        boolean foundElement = false;
        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                objectDepth++;
            } else if (character == '}') {
                objectDepth--;
            } else if (character == '[') {
                arrayDepth++;
            } else if (character == ']') {
                arrayDepth--;
                if (arrayDepth == 0) {
                    String type = classifyElement(text, elementStart, index);
                    if (type != null) {
                        foundElement = true;
                        if (commonType == null) {
                            commonType = type;
                        } else if (!commonType.equals(type)) {
                            return "mixed";
                        }
                    }
                    break;
                }
            } else if (character == ',' && objectDepth == 0 && arrayDepth == 1) {
                String type = classifyElement(text, elementStart, index);
                if (type != null) {
                    foundElement = true;
                    if (commonType == null) {
                        commonType = type;
                    } else if (!commonType.equals(type)) {
                        return "mixed";
                    }
                }
                elementStart = index + 1;
            }
        }
        return foundElement ? commonType : "unknown";
    }

    private static String classifyElement(String text, int start, int end) {
        int first = firstNonWhitespace(text, start, end);
        if (first < 0) {
            return null;
        }
        return scanRange(text, first, end);
    }

    private static String scanRange(String text, int start, int end) {
        int key = text.indexOf(KEY, start);
        if (key < 0 || key >= end) {
            return "unknown";
        }
        int colon = text.indexOf(':', key + KEY.length());
        if (colon < 0 || colon >= end) {
            return "unknown";
        }
        int quote = text.indexOf('"', colon + 1);
        if (quote < 0 || quote >= end) {
            return "unknown";
        }
        int valueEnd = text.indexOf('"', quote + 1);
        if (valueEnd < 0
                || valueEnd >= end
                || valueEnd - quote - 1 > MAX_EVENT_TYPE_LENGTH) {
            return "unknown";
        }
        return text.substring(quote + 1, valueEnd);
    }

    private static int firstNonWhitespace(String text, int start, int end) {
        for (int index = start; index < end; index++) {
            if (!Character.isWhitespace(text.charAt(index))) {
                return index;
            }
        }
        return -1;
    }
}
