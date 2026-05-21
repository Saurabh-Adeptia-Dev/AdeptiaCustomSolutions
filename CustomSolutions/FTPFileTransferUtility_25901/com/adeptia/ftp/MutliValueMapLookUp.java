package com.adeptia.ftp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;

/**
 * Parses SIC_EENAV.xml and performs GroupId ? mapped value lookups.
 *
 * XML structure expected:
 *   MultiValuedMapDetails
 *     MultiValuedMaps
 *       MultiValuedMap
 *         Value         ? GroupId key
 *         Map2          ? TransactionName
 *         Map3          ? TransactionPath
 *     MapValueFieldNames
 *       MapValueFieldName
 *         DisplayName   ? "TransactionName" or "TransactionPath"
 *         FieldName     ? "Map2" or "Map3"
 *         DefaultValue
 *           Value
 *           PickFromSource
 */
public class MutliValueMapLookUp {

    private static final Logger LOG = LoggerFactory.getLogger(MutliValueMapLookUp.class);

    private final Document xmlDoc;

    // ?? Constructor ????????????????????????????????????????????????????????

    /**
     * Load and parse SIC_EENAV.xml from the given file path.
     */
    public MutliValueMapLookUp(String xmlFilePath) throws Exception {
        File file = new File(xmlFilePath);
        if (!file.exists()) {
            throw new FileNotFoundException("Value Map  is not found at: " + xmlFilePath);
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        this.xmlDoc = builder.parse(file);
        this.xmlDoc.getDocumentElement().normalize();
    }

    // ?? Public API ?????????????????????????????????????????????????????????

    /**
     * Lookup a mapped value by GroupId and DisplayName.
     *
     * @param groupId          e.g. "101"
     * @param displayName      e.g. "TransactionPath" or "TransactionName"
     * @return                 mapped value, or default/fallback if not found
     *
     * Examples:
     *   lookup("101", "TransactionPath") ? "EENav_Custom1"
     *   lookup("101", "TransactionName") ? "EENav_101_Transaction"
     *   lookup("999", "TransactionPath") ? "EENav_Base"  (default)
     */
    public String lookup(String groupId, String displayName) {
        // Step 1: Resolve FieldName from DisplayName  (e.g. "TransactionPath" ? "Map3")
        String fieldName     = getFieldName(displayName);
        String defaultValue  = getDefaultValue(displayName);
        String pickFromSrc   = getPickFromSource(displayName);

        LOG.debug("ValueMap GroupId={} DisplayName={} FieldName={} Default={}",
                groupId, displayName, fieldName, defaultValue);

        if (fieldName == null || fieldName.isEmpty()) {
            LOG.warn("ValueMap FieldName not found for displayName=[{}], returning default.", displayName);
            return defaultValue != null ? defaultValue : "";
        }

        // Step 2: Find MultiValuedMap where <Value> matches groupId
        NodeList maps = xmlDoc.getElementsByTagName("MultiValuedMap");
        for (int i = 0; i < maps.getLength(); i++) {
            Node n = maps.item(i);
            // Skip non-Element nodes (text nodes, whitespace, comments)
            // getTagName() returns null for these and causes NullPointerException
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element map  = (Element) n;
            String value = getChildText(map, "Value");

            if (groupId != null && groupId.trim().equals(value != null ? value.trim() : "")) {
                // Step 3: Return the field value (e.g. Map3 -> "EENav_Custom1")
                String mapped = getChildText(map, fieldName);
                if (mapped != null && !mapped.isEmpty()) {
                    LOG.debug("ValueMap match found for GroupId=[{}]: {}", groupId, mapped);
                    return mapped;
                }
            }
        }

        // Step 4: No match ? apply fallback
        LOG.debug("ValueMap no match for GroupId=[{}].", groupId);
        if ("true".equalsIgnoreCase(pickFromSrc != null ? pickFromSrc.trim() : "")) {
            LOG.debug("ValueMap PickFromSource=true, returning groupId=[{}].", groupId);
            return groupId;
        }
        LOG.debug("ValueMap returning default=[{}].", defaultValue);
        return defaultValue != null ? defaultValue : "";
    }

    // ?? Private Helpers ????????????????????????????????????????????????????

    private String getFieldName(String displayName) {
        return getMapValueFieldProperty(displayName, "FieldName");
    }

    private String getDefaultValue(String displayName) {
        NodeList fieldNames = xmlDoc.getElementsByTagName("MapValueFieldName");
        for (int i = 0; i < fieldNames.getLength(); i++) {
            Node n = fieldNames.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element fn = (Element) n;
            String dn  = getChildText(fn, "DisplayName");
            if (displayName.equals(dn)) {
                NodeList defList = fn.getElementsByTagName("DefaultValue");
                if (defList.getLength() > 0) {
                    Node defNode = defList.item(0);
                    if (defNode.getNodeType() == Node.ELEMENT_NODE) {
                        return getChildText((Element) defNode, "Value");
                    }
                }
            }
        }
        return "";
    }

    private String getPickFromSource(String displayName) {
        NodeList fieldNames = xmlDoc.getElementsByTagName("MapValueFieldName");
        for (int i = 0; i < fieldNames.getLength(); i++) {
            Node n = fieldNames.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element fn = (Element) n;
            String dn  = getChildText(fn, "DisplayName");
            if (displayName.equals(dn)) {
                NodeList defList = fn.getElementsByTagName("DefaultValue");
                if (defList.getLength() > 0) {
                    Node defNode = defList.item(0);
                    if (defNode.getNodeType() == Node.ELEMENT_NODE) {
                        return getChildText((Element) defNode, "PickFromSource");
                    }
                }
            }
        }
        return "false";
    }

    private String getMapValueFieldProperty(String displayName, String property) {
        NodeList fieldNames = xmlDoc.getElementsByTagName("MapValueFieldName");
        for (int i = 0; i < fieldNames.getLength(); i++) {
            Node n = fieldNames.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element fn = (Element) n;
            String dn  = getChildText(fn, "DisplayName");
            if (displayName.equals(dn)) {
                return getChildText(fn, property);
            }
        }
        return "";
    }

    /**
     * Gets the direct child element text content (not nested descendants).
     */
    private String getChildText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            Node node = nodes.item(0);
            return node.getTextContent().trim();
        }
        return "";
    }
}