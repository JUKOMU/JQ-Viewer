package io.github.jukomu.desktop.feature.export.archive;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** ComicInfo v2.0 的最小双向 codec；缺失字段按空值处理。 */
public final class ComicInfoCodec {
    private ComicInfoCodec() {
    }

    public static byte[] serialize(ComicInfo info) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            XMLStreamWriter xml = XMLOutputFactory.newFactory()
                    .createXMLStreamWriter(output, StandardCharsets.UTF_8.name());
            xml.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            xml.writeStartElement("ComicInfo");
            xml.writeNamespace("xsi", "http://www.w3.org/2001/XMLSchema-instance");
            xml.writeNamespace("xsd", "http://www.w3.org/2001/XMLSchema");
            element(xml, "Title", info.title());
            element(xml, "Series", info.series());
            element(xml, "Number", info.number());
            element(xml, "Writer", info.writer());
            element(xml, "Web", info.web());
            element(xml, "PageCount", info.pageCount() > 0
                    ? String.valueOf(info.pageCount()) : null);
            element(xml, "Manga", info.manga());
            element(xml, "Volume", info.volume() == null ? null : String.valueOf(info.volume()));
            element(xml, "Count", info.count() == null ? null : String.valueOf(info.count()));
            if (!info.pages().isEmpty()) {
                xml.writeStartElement("Pages");
                for (ComicInfo.Page page : info.pages()) {
                    xml.writeEmptyElement("Page");
                    xml.writeAttribute("Image", String.valueOf(page.image()));
                    if (page.bookmark() != null && !page.bookmark().isBlank()) {
                        xml.writeAttribute("Bookmark", page.bookmark());
                    }
                    if (page.type() != null && !page.type().isBlank()) {
                        xml.writeAttribute("Type", page.type());
                    }
                }
                xml.writeEndElement();
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("ComicInfo 序列化失败", exception);
        }
    }

    public static ComicInfo parse(byte[] content) {
        if (content == null) throw new IllegalArgumentException("ComicInfo 内容不能为空");
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(content)).getDocumentElement();
            List<ComicInfo.Page> pages = new ArrayList<>();
            NodeList nodes = root.getElementsByTagName("Page");
            for (int index = 0; index < nodes.getLength(); index++) {
                Element page = (Element) nodes.item(index);
                pages.add(new ComicInfo.Page(integer(page.getAttribute("Image"), index),
                        value(page.getAttribute("Bookmark")), value(page.getAttribute("Type"))));
            }
            return new ComicInfo(
                    text(root, "Title"), text(root, "Series"), text(root, "Number"),
                    text(root, "Writer"), text(root, "Web"),
                    integer(text(root, "PageCount"), pages.size()), text(root, "Manga"),
                    nullableInteger(text(root, "Volume")), nullableInteger(text(root, "Count")),
                    pages);
        } catch (Exception exception) {
            throw new IllegalArgumentException("ComicInfo 解析失败", exception);
        }
    }

    private static void element(XMLStreamWriter xml, String name, String value) throws Exception {
        if (value == null || value.isBlank()) return;
        xml.writeStartElement(name);
        xml.writeCharacters(value);
        xml.writeEndElement();
    }

    private static String text(Element root, String tag) {
        NodeList values = root.getElementsByTagName(tag);
        return values.getLength() == 0 ? null : value(values.item(0).getTextContent());
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int integer(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Integer nullableInteger(String value) {
        return value == null ? null : integer(value, 0);
    }
}
