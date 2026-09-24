package io.github.jukomu.feature.export.archive;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** ComicInfo v2.0 的最小双向 codec；缺失字段按空值处理。 */
public final class ComicInfoCodec {
    private ComicInfoCodec() {
    }

    public static byte[] serialize(ComicInfo info) {
        try {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element root = document.createElement("ComicInfo");
            root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
            root.setAttribute("xmlns:xsd", "http://www.w3.org/2001/XMLSchema");
            document.appendChild(root);
            element(document, root, "Title", info.title);
            element(document, root, "Series", info.series);
            element(document, root, "Number", info.number);
            element(document, root, "Writer", info.writer);
            element(document, root, "Web", info.web);
            element(document, root, "PageCount", info.pageCount > 0
                ? String.valueOf(info.pageCount) : null);
            element(document, root, "Manga", info.manga);
            element(document, root, "Volume", info.volume == null ? null : String.valueOf(info.volume));
            element(document, root, "Count", info.count == null ? null : String.valueOf(info.count));
            if (!info.pages.isEmpty()) {
                Element pages = document.createElement("Pages");
                root.appendChild(pages);
                for (ComicInfo.Page value : info.pages) {
                    Element page = document.createElement("Page");
                    page.setAttribute("Image", String.valueOf(value.image));
                    if (value.bookmark != null && !value.bookmark.trim().isEmpty()) {
                        page.setAttribute("Bookmark", value.bookmark);
                    }
                    if (value.type != null && !value.type.trim().isEmpty()) {
                        page.setAttribute("Type", value.type);
                    }
                    pages.appendChild(page);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("ComicInfo 序列化失败", error);
        }
    }

    public static ComicInfo parse(byte[] content) {
        if (content == null) throw new IllegalArgumentException("ComicInfo 内容不能为空");
        try {
            Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
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
                nullableInteger(text(root, "Volume")), nullableInteger(text(root, "Count")), pages);
        } catch (Exception error) {
            throw new IllegalArgumentException("ComicInfo 解析失败", error);
        }
    }

    private static void element(Document document, Element root, String name, String value) {
        if (value == null || value.trim().isEmpty()) return;
        Element child = document.createElement(name);
        child.setTextContent(value);
        root.appendChild(child);
    }

    private static String text(Element root, String tag) {
        NodeList values = root.getElementsByTagName(tag);
        return values.getLength() == 0 ? null : value(values.item(0).getTextContent());
    }

    private static String value(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static int integer(String value, int fallback) {
        try {
            return value == null || value.trim().isEmpty() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Integer nullableInteger(String value) {
        return value == null ? null : integer(value, 0);
    }
}
