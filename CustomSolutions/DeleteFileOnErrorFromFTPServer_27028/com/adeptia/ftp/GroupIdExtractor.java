package com.adeptia.ftp;

/**
 * Extracts GroupId from a filename following the pattern:
 *   prefix_GroupId_suffix.ext
 *   e.g. EENav_101_Transaction.csv  ?  "101"
 *
 * Mirrors the XSL logic:
 *   substring-before( substring-after($fileName, '_'), '_' )
 */
public class GroupIdExtractor {

    private GroupIdExtractor() {}

    /**
     * @param fileName  raw filename or full path
     * @return          extracted GroupId, or empty string if not found
     */
    public static String extract(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "";
        }

        // Strip directory path if present
        String name = fileName;
        if (name.contains("/"))  name = name.substring(name.lastIndexOf("/")  + 1);
        if (name.contains("\\")) name = name.substring(name.lastIndexOf("\\") + 1);

        // substring-after first '_'
        int firstUnderscore = name.indexOf('_');
        if (firstUnderscore == -1 || firstUnderscore == name.length() - 1) {
            return "";
        }
        String afterFirst = name.substring(firstUnderscore + 1);

        // substring-before second '_'
        int secondUnderscore = afterFirst.indexOf('_');
        if (secondUnderscore == -1) {
            // No second underscore ? return everything after the first
            return afterFirst.contains(".")
                    ? afterFirst.substring(0, afterFirst.lastIndexOf('.'))
                    : afterFirst;
        }

        return afterFirst.substring(0, secondUnderscore);
    }
}