import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class XMLReader {
  private static final Logger log = Logger.getLogger(XMLReader.class);

  private static final XMLReader INSTANCE = new XMLReader();

  private static final String VALUE_MAP_ROOT = "/shared/web/applets/mapping/valueMap";

  private static final String MULTI_VALUE_MAP_ROOT = "/shared/web/applets/mapping/multiValuedMap";

  private static final String DEFAULT_PROJECT_SENTINEL = "Default";

  // ---------------------------------------------------------------------------
  // Sentinel used internally to signal "PickFromSource = true" so the caller
  // knows to return the original source key rather than a hard-coded default.
  // ---------------------------------------------------------------------------
  private static final String PICK_FROM_SOURCE_SENTINEL = "__PICK_FROM_SOURCE__";

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  @Comment("Looks up a mapped value from a ValueMap XML file. Accepts 3 parameters-<br><br>"
      + "<b>1.</b> valueMapName - Name of the ValueMap without .xml e.g. VM_StatusCodes<br><br>"
      + "<b>2.</b> keymap - Source key to look up case-sensitive<br><br>"
      + "<b>3.</b> projectID - Project ID for project-scoped maps. Pass empty string for Global. "
      + "Pass 'Default' for Default project. Pass the numeric project ID (e.g. '987421061565693952') "
      + "for a specific project.<br><br>"
      + "Returns matched value. If the key is not found and overrideSource=true, returns the sourceKey itself. "
      + "If the key is not found and a static defaultValue is configured, returns that. "
      + "Returns null if no default is configured.")
  public static String getValueFromValueMap(String paramString1, String paramString2, String paramString3) {
    if (paramString1 == null || paramString1.trim().isEmpty()) {
      log.error("getValueFromValueMap: valueMapName is null or empty.");
      return null;
    }
    if (paramString2 == null || paramString2.trim().isEmpty()) {
      log.error("getValueFromValueMap: keymap is null or empty.");
      return null;
    }
    return INSTANCE.lookupValueMap(
        INSTANCE.resolveFilePath("/shared/web/applets/mapping/valueMap", paramString1, paramString3),
        paramString2, paramString2);
  }

  @Comment("Looks up a column value from a MultiValuedMap XML file. Accepts 4 parameters-<br><br>"
      + "<b>1.</b> mapName - Name of the MultiValuedMap without .xml<br><br>"
      + "<b>2.</b> sourceKey - Source key to match case-sensitive<br><br>"
      + "<b>3.</b> mapColumn - Column to retrieve accepts FieldName e.g. map1 OR DisplayName e.g. AbsenceReason case-insensitive<br><br>"
      + "<b>4.</b> projectID - Project ID for project-scoped maps. Pass empty string for Global. "
      + "Pass 'Default' for Default project. Pass the numeric project ID (e.g. '987421061565693952') "
      + "for a specific project.<br><br>"
      + "Returns matched column value. If the key is not found and the DefaultValue has PickFromSource=true, "
      + "returns the sourceKey itself. If the key is not found and a static DefaultValue is configured, returns that. "
      + "Returns null if no default is configured. Throws IllegalArgumentException if mapColumn is invalid.")
  public static String getValueFromMultiValueMap(String paramString1, String paramString2,
      String paramString3, String paramString4) {
    if (paramString1 == null || paramString1.trim().isEmpty()) {
      log.error("getValueFromMultiValueMap: mapName is null or empty.");
      return null;
    }
    if (paramString2 == null || paramString2.trim().isEmpty()) {
      log.error("getValueFromMultiValueMap: sourceKey is null or empty.");
      return null;
    }
    if (paramString3 == null || paramString3.trim().isEmpty()) {
      log.error("getValueFromMultiValueMap: mapColumn is null or empty.");
      return null;
    }
    return INSTANCE.lookupMultiValueMap(
        INSTANCE.resolveFilePath("/shared/web/applets/mapping/multiValuedMap", paramString1, paramString4),
        paramString1, paramString2, paramString3);
  }

  // ---------------------------------------------------------------------------
  // Path helpers
  // ---------------------------------------------------------------------------

  private String resolveFilePath(String paramString1, String paramString2, String paramString3) {
    if (paramString3 == null || paramString3.trim().isEmpty())
      return buildFilePath(paramString1, paramString2, null);
    return buildFilePath(paramString1, paramString2, paramString3.trim());
  }

  private String buildFilePath(String paramString1, String paramString2, String paramString3) {
    if (paramString3 == null || paramString3.isEmpty())
      return paramString1 + File.separator + "global" + File.separator + paramString2.trim() + ".xml";
    return paramString1 + File.separator + "project" + File.separator + paramString3 + "_"
        + paramString2.trim() + ".xml";
  }

  // ---------------------------------------------------------------------------
  // ValueMap lookup — updated to handle overrideSource
  // ---------------------------------------------------------------------------

  /**
   * Core lookup for ValueMap.
   *
   * <p>Fall-through order when no row matches {@code sourceKey}:
   * <ol>
   *   <li>If the {@code <valueMap>} block has {@code <overrideSource>true</overrideSource>},
   *       return {@code sourceKey} as-is (pass-through behaviour).</li>
   *   <li>If a non-empty {@code <defaultValue>} is present, return that static string.</li>
   *   <li>Otherwise return {@code null}.</li>
   * </ol>
   */
  private String lookupValueMap(String filePath, String sourceKey, String keymap) {
    log.info("lookupValueMap: Reading file -> " + filePath);
    String matchedValue = null;
    try {
      File file = new File(filePath);
      if (!file.exists()) {
        log.error("lookupValueMap: File not found: " + filePath);
        return null;
      }
      Document document = parseXml(file);
      if (document == null)
        return null;
      Element root = document.getDocumentElement();
      root.normalize();

      NodeList vmNodes = root.getElementsByTagName("valueMap");
      if (vmNodes.getLength() == 0) {
        log.error("lookupValueMap: No valueMap elements in: " + filePath);
        return null;
      }

      // Scan all <valueMap> blocks (a file can hold more than one map)
      outer:
      for (int i = 0; i < vmNodes.getLength(); i++) {
        Node vmNode = vmNodes.item(i);
        if (vmNode.getNodeType() != Node.ELEMENT_NODE)
          continue;
        Element vmEl = (Element) vmNode;

        // Scan <valueMapDetails> rows inside this block
        NodeList detailNodes = vmEl.getElementsByTagName("valueMapDetails");
        for (int j = 0; j < detailNodes.getLength(); j++) {
          Node detailNode = detailNodes.item(j);
          if (detailNode.getNodeType() != Node.ELEMENT_NODE)
            continue;
          Element detailEl = (Element) detailNode;
          NodeList valueNodes = detailEl.getElementsByTagName("value");
          if (valueNodes.getLength() != 0
              && valueNodes.item(0).getTextContent().trim().equals(keymap)) {
            matchedValue = detailEl.getElementsByTagName("map").item(0).getTextContent().trim();
            break outer;
          }
        }

        // No row matched — check overrideSource and defaultValue on this <valueMap>
        if (matchedValue == null) {
          // --- overrideSource check ---
          NodeList osNodes = vmEl.getElementsByTagName("overrideSource");
          if (osNodes.getLength() > 0) {
            String osValue = osNodes.item(0).getTextContent().trim();
            if ("true".equalsIgnoreCase(osValue)) {
              log.info("lookupValueMap: Key '" + keymap
                  + "' not found and overrideSource=true. Returning sourceKey: '" + sourceKey + "'");
              return sourceKey;
            }
          }

          // --- static defaultValue fallback ---
          String defaultVal = readValueMapDefault(vmEl);
          if (defaultVal != null && !defaultVal.isEmpty()) {
            log.info("lookupValueMap: Key '" + keymap
                + "' not found. Returning static default: '" + defaultVal + "'");
            return defaultVal;
          }

          log.error("lookupValueMap: No match and no default for key '" + keymap + "' in: " + filePath);
        }
      }
    } catch (Exception exception) {
      log.error("lookupValueMap: Error reading '" + filePath + "': " + exception.getMessage(), exception);
    }
    return matchedValue;
  }

  // ---------------------------------------------------------------------------
  // MultiValuedMap lookup — updated to handle PickFromSource
  // ---------------------------------------------------------------------------

  /**
   * Core lookup for MultiValuedMap.
   *
   * <p>Fall-through order when no row matches {@code sourceKey}:
   * <ol>
   *   <li>If the column's {@code <DefaultValue>} has {@code <PickFromSource>true</PickFromSource>},
   *       return {@code sourceKey} as-is.</li>
   *   <li>If a non-empty {@code <Value>} exists inside {@code <DefaultValue>}, return that.</li>
   *   <li>Otherwise return {@code null}.</li>
   * </ol>
   */
  private String lookupMultiValueMap(String filePath, String mapName,
      String sourceKey, String mapColumn) {
    log.info("lookupMultiValueMap: Reading file -> " + filePath);
    try {
      File file = new File(filePath);
      if (!file.exists()) {
        log.error("lookupMultiValueMap: File not found: " + filePath);
        return null;
      }
      Document document = parseXml(file);
      if (document == null)
        return null;
      Element root = document.getDocumentElement();
      root.normalize();

      // Resolve the requested column display/field name → internal FieldName tag
      String resolvedColumn = resolveColumnName(root, mapColumn);
      if (resolvedColumn == null) {
        List<String> validNames = extractDisplayNames(root);
        String msg = "Invalid mapColumn '" + mapColumn + "' for map '" + mapName
            + "'. Valid column names: " + validNames;
        log.error("lookupMultiValueMap: " + msg);
        throw new IllegalArgumentException(msg);
      }
      log.info("lookupMultiValueMap: Column '" + mapColumn + "' resolved to '" + resolvedColumn + "'");

      // Scan rows for a matching source key
      NodeList rows = root.getElementsByTagName("MultiValuedMap");
      if (rows.getLength() == 0) {
        log.error("lookupMultiValueMap: No MultiValuedMap entries in: " + filePath);
        return null;
      }
      for (int i = 0; i < rows.getLength(); i++) {
        Node node = rows.item(i);
        if (node.getNodeType() != Node.ELEMENT_NODE)
          continue;
        Element row = (Element) node;
        NodeList valueNodes = row.getElementsByTagName("Value");
        if (valueNodes.getLength() == 0)
          continue;
        if (valueNodes.item(0).getTextContent().trim().equals(sourceKey)) {
          // Found the matching row — return the requested column value
          NodeList colNodes = row.getElementsByTagName(resolvedColumn);
          if (colNodes.getLength() > 0)
            return colNodes.item(0).getTextContent().trim();
          log.error("lookupMultiValueMap: Column '" + resolvedColumn
              + "' missing in matched row for key '" + sourceKey + "'");
          return null;
        }
      }

      // -----------------------------------------------------------------------
      // No row matched — evaluate the DefaultValue for the requested column.
      // readMultiValueMapDefault now returns PICK_FROM_SOURCE_SENTINEL when
      // <PickFromSource>true</PickFromSource> is set, so we can return the
      // original sourceKey in that case.
      // -----------------------------------------------------------------------
      String defaultResult = readMultiValueMapDefault(root, resolvedColumn);

      if (PICK_FROM_SOURCE_SENTINEL.equals(defaultResult)) {
        log.info("lookupMultiValueMap: Key '" + sourceKey
            + "' not found and PickFromSource=true. Returning sourceKey: '" + sourceKey + "'");
        return sourceKey;
      }

      if (defaultResult != null && !defaultResult.isEmpty()) {
        log.info("lookupMultiValueMap: Key '" + sourceKey
            + "' not found. Returning static default: '" + defaultResult + "'");
        return defaultResult;
      }

      log.error("lookupMultiValueMap: No match and no default for sourceKey '"
          + sourceKey + "' in: " + filePath);

    } catch (IllegalArgumentException iae) {
      throw iae;
    } catch (Exception exception) {
      log.error("lookupMultiValueMap: Error reading '" + filePath + "': "
          + exception.getMessage(), exception);
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Default-value readers
  // ---------------------------------------------------------------------------

  /**
   * Reads the static {@code <defaultValue>} text from a single {@code <valueMap>} element.
   * Returns the trimmed string if non-empty, {@code null} otherwise.
   * (overrideSource is handled directly in {@link #lookupValueMap}, not here.)
   */
  private String readValueMapDefault(Element vmElement) {
    try {
      NodeList nodeList = vmElement.getElementsByTagName("defaultValue");
      if (nodeList.getLength() > 0) {
        String str = nodeList.item(0).getTextContent().trim();
        if (!str.isEmpty())
          return str;
      }
    } catch (Exception exception) {
      log.error("readValueMapDefault: Error reading default value: " + exception.getMessage(), exception);
    }
    return null;
  }

  /**
   * Reads the {@code <DefaultValue>} block for the given {@code columnFieldName}.
   *
   * <p>Returns:
   * <ul>
   *   <li>{@link #PICK_FROM_SOURCE_SENTINEL} — when {@code <PickFromSource>true</PickFromSource>}</li>
   *   <li>The static default string — when {@code <PickFromSource>false</PickFromSource>} and
   *       a non-empty {@code <Value>} is present.</li>
   *   <li>{@code null} — when no usable default is found.</li>
   * </ul>
   */
  private String readMultiValueMapDefault(Element root, String columnFieldName) {
    try {
      NodeList fieldNodes = root.getElementsByTagName("MapValueFieldName");
      for (int i = 0; i < fieldNodes.getLength(); i++) {
        Node node = fieldNodes.item(i);
        if (node.getNodeType() != Node.ELEMENT_NODE)
          continue;
        Element fieldEl = (Element) node;

        // Match by internal FieldName
        NodeList fnNodes = fieldEl.getElementsByTagName("FieldName");
        if (fnNodes.getLength() == 0)
          continue;
        if (!fnNodes.item(0).getTextContent().trim().equals(columnFieldName))
          continue;

        // Found the column definition — read its DefaultValue block
        NodeList dvNodes = fieldEl.getElementsByTagName("DefaultValue");
        if (dvNodes.getLength() == 0)
          return null;
        Element dvEl = (Element) dvNodes.item(0);

        // --- Check PickFromSource first ---
        NodeList pfsNodes = dvEl.getElementsByTagName("PickFromSource");
        if (pfsNodes.getLength() > 0) {
          String pfsValue = pfsNodes.item(0).getTextContent().trim();
          if ("true".equalsIgnoreCase(pfsValue)) {
            return PICK_FROM_SOURCE_SENTINEL; // caller will substitute sourceKey
          }
        }

        // --- Fall back to static <Value> ---
        NodeList valNodes = dvEl.getElementsByTagName("Value");
        if (valNodes.getLength() > 0) {
          String staticDefault = valNodes.item(0).getTextContent().trim();
          if (!staticDefault.isEmpty())
            return staticDefault;
        }
      }
    } catch (Exception exception) {
      log.error("readMultiValueMapDefault: Error reading default for column '"
          + columnFieldName + "': " + exception.getMessage(), exception);
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // XML helpers
  // ---------------------------------------------------------------------------

  private Document parseXml(File paramFile) {
    try {
      DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
      return documentBuilder.parse(paramFile);
    } catch (Exception exception) {
      log.error("parseXml: Failed to parse: " + paramFile.getAbsolutePath()
          + ". Error: " + exception.getMessage(), exception);
      return null;
    }
  }

  private String resolveColumnName(Element paramElement, String paramString) {
    NodeList nodeList = paramElement.getElementsByTagName("MapValueFieldName");
    for (int b = 0; b < nodeList.getLength(); b++) {
      Node node = nodeList.item(b);
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        Element element = (Element) node;
        String fieldName = "";
        NodeList fnNodes = element.getElementsByTagName("FieldName");
        if (fnNodes.getLength() > 0)
          fieldName = fnNodes.item(0).getTextContent().trim();
        String displayName = "";
        NodeList dnNodes = element.getElementsByTagName("DisplayName");
        if (dnNodes.getLength() > 0)
          displayName = dnNodes.item(0).getTextContent().trim();
        if ((!fieldName.isEmpty() && fieldName.equalsIgnoreCase(paramString))
            || (!displayName.isEmpty() && displayName.equalsIgnoreCase(paramString)))
          return fieldName;
      }
    }
    return null;
  }

  private List<String> extractDisplayNames(Element paramElement) {
    ArrayList<String> list = new ArrayList<>();
    NodeList nodeList = paramElement.getElementsByTagName("MapValueFieldName");
    for (int b = 0; b < nodeList.getLength(); b++) {
      Node node = nodeList.item(b);
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        Element element = (Element) node;
        String fieldName = "";
        NodeList fnNodes = element.getElementsByTagName("FieldName");
        if (fnNodes.getLength() > 0)
          fieldName = fnNodes.item(0).getTextContent().trim();
        String displayName = "";
        NodeList dnNodes = element.getElementsByTagName("DisplayName");
        if (dnNodes.getLength() > 0)
          displayName = dnNodes.item(0).getTextContent().trim();
        if (!fieldName.isEmpty())
          list.add(!displayName.isEmpty() ? displayName : fieldName);
      }
    }
    return list;
  }
}
