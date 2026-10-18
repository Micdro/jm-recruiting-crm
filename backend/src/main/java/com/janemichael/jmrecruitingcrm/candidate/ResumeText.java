package com.janemichael.jmrecruitingcrm.candidate;

import com.janemichael.jmrecruitingcrm.candidate.CandidateFileService.Kind;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The words of a resume, for AI to read: PDF, Word (.docx), OpenDocument, RTF and text files.
 * Links in the file are listed at the end, since a LinkedIn address often sits behind the word
 * "LinkedIn" and appears nowhere else.
 */
final class ResumeText {

    /** Far more than any resume; keeps a huge file from running up the AI bill. */
    static final int MAX_CHARS = 30_000;
    static final int MAX_PDF_PAGES = 20;
    /** Less than this is a scanned picture or an empty file, not a resume. */
    static final int MIN_CHARS = 40;
    private static final int MAX_PART_BYTES = 20 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 5_000;
    private static final int MAX_LINKS = 30;
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final Pattern HEADER = Pattern.compile("word/header\\d*\\.xml");
    private static final String DOCX_BODY = "word/document.xml";
    private static final String DOCX_LINKS = "word/_rels/document.xml.rels";

    private ResumeText() {
    }

    /**
     * @throws BadRequestException when the file can't be read, saying what to do instead
     */
    static String read(String fileName, byte[] content) {
        Kind kind = fileName == null ? null : Kind.forName(fileName);
        if (kind == null) {
            throw new BadRequestException("Drop in a PDF, Word (.docx), OpenDocument (.odt), RTF or text file.");
        }
        if (content.length == 0) {
            throw new BadRequestException("That file is empty.");
        }
        if (content.length > CandidateFileService.MAX_BYTES) {
            throw new BadRequestException("Files can be up to 10 MB.");
        }
        if (!kind.matches(content)) {
            throw new BadRequestException("That file isn't a real ." + kind.extension + " file.");
        }
        return read(kind, content);
    }

    static String read(Kind kind, byte[] content) {
        Set<String> links = new LinkedHashSet<>();
        String text = switch (kind) {
            case PDF -> pdf(content, links);
            case DOCX -> docx(content, links);
            case ODT -> odt(content, links);
            case RTF -> rtf(content);
            case TXT -> txt(content);
            case DOC -> throw new BadRequestException("Old Word files (.doc) can't be read. Open it in Word, save it "
                    + "as .docx or PDF, and use that.");
        };
        String tidy = tidy(text);
        if (tidy.length() < MIN_CHARS) {
            throw new BadRequestException(kind == Kind.PDF
                    ? "This PDF has no text to read. It's likely a scanned picture. Ask for the original file, or "
                    + "fill in the candidate by hand."
                    : "There's no text in this file to read.");
        }
        List<String> kept = links.stream().filter(ResumeText::usefulLink).map(String::trim).distinct()
                .limit(MAX_LINKS).toList();
        String full = kept.isEmpty() ? tidy : tidy + "\n\nLinks in the file:\n" + String.join("\n", kept);
        return full.length() > MAX_CHARS ? full.substring(0, MAX_CHARS) : full;
    }

    // PDF ------------------------------------------------------------------------------------

    private static String pdf(byte[] content, Set<String> links) {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(MAX_PDF_PAGES);
            String text = stripper.getText(document);
            int pages = 0;
            for (PDPage page : document.getPages()) {
                if (++pages > MAX_PDF_PAGES) {
                    break;
                }
                for (PDAnnotation annotation : page.getAnnotations()) {
                    if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri) {
                        links.add(uri.getURI());
                    }
                }
            }
            return text;
        } catch (InvalidPasswordException locked) {
            throw new BadRequestException("This PDF is locked with a password. Open it, save a copy without the "
                    + "password, and use that.");
        } catch (IOException | RuntimeException broken) {
            throw new BadRequestException("This PDF couldn't be read. Try saving it again as a PDF.");
        }
    }

    // Word ------------------------------------------------------------------------------------

    private static String docx(byte[] content, Set<String> links) {
        Map<String, byte[]> parts = unzip(content, name -> name.equals(DOCX_BODY) || name.equals(DOCX_LINKS)
                || HEADER.matcher(name).matches());
        byte[] body = parts.get(DOCX_BODY);
        if (body == null) {
            throw new BadRequestException("This Word file couldn't be read. Try saving it again as .docx or PDF.");
        }
        StringBuilder text = new StringBuilder();
        // Contact details often sit in the page header.
        parts.forEach((name, part) -> {
            if (HEADER.matcher(name).matches()) {
                text.append(wordXml(part)).append('\n');
            }
        });
        text.append(wordXml(body));
        if (parts.containsKey(DOCX_LINKS)) {
            wordLinks(parts.get(DOCX_LINKS), links);
        }
        return text.toString();
    }

    /** A Word part's text: runs, tabs, line breaks and paragraphs. */
    static String wordXml(byte[] xml) {
        StringBuilder text = new StringBuilder();
        walk(xml, new Visitor() {
            @Override
            public boolean skip(String name) {
                // A text box comes twice: the modern shape, then an old-style copy as a fallback.
                return name.equals("Fallback");
            }

            @Override
            public void start(String name, String parent, XMLStreamReader reader) {
                switch (name) {
                    case "tab" -> {
                        if ("r".equals(parent)) {
                            text.append('\t');
                        }
                    }
                    case "br", "cr" -> text.append('\n');
                    case "noBreakHyphen" -> text.append('-');
                    default -> {
                    }
                }
            }

            @Override
            public void end(String name) {
                if (name.equals("p")) {
                    text.append('\n');
                }
            }

            @Override
            public void text(String inside, String value) {
                if ("t".equals(inside)) {
                    text.append(value);
                }
            }
        });
        return text.toString();
    }

    private static void wordLinks(byte[] xml, Set<String> links) {
        walk(xml, new Visitor() {
            @Override
            public void start(String name, String parent, XMLStreamReader reader) {
                String type = reader.getAttributeValue(null, "Type");
                if (name.equals("Relationship") && type != null && type.endsWith("/hyperlink")) {
                    links.add(reader.getAttributeValue(null, "Target"));
                }
            }
        });
    }

    // OpenDocument ----------------------------------------------------------------------------

    private static String odt(byte[] content, Set<String> links) {
        byte[] body = unzip(content, name -> name.equals("content.xml")).get("content.xml");
        if (body == null) {
            throw new BadRequestException("This OpenDocument file couldn't be read. Try saving it again as .odt or PDF.");
        }
        StringBuilder text = new StringBuilder();
        int[] paragraphs = {0};
        walk(body, new Visitor() {
            @Override
            public boolean skip(String name) {
                // Comments and deleted tracked changes aren't part of the resume.
                return name.equals("annotation") || name.equals("tracked-changes");
            }

            @Override
            public void start(String name, String parent, XMLStreamReader reader) {
                switch (name) {
                    case "p", "h" -> paragraphs[0]++;
                    case "s" -> text.append(' ');
                    case "tab" -> text.append('\t');
                    case "line-break" -> text.append('\n');
                    case "a" -> links.add(reader.getAttributeValue(null, "href"));
                    default -> {
                    }
                }
            }

            @Override
            public void end(String name) {
                if (name.equals("p") || name.equals("h")) {
                    paragraphs[0]--;
                    text.append('\n');
                }
            }

            @Override
            public void text(String inside, String value) {
                if (paragraphs[0] > 0) {
                    text.append(value);
                }
            }
        });
        return text.toString();
    }

    // RTF -------------------------------------------------------------------------------------

    /** Groups whose text isn't part of the document: fonts, colors, styles, pictures and the like. */
    private static final Set<String> RTF_SKIPPED = Set.of("fonttbl", "colortbl", "stylesheet", "info", "pict",
            "object", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "themedata",
            "colorschememapping", "datastore", "latentstyles", "footer", "footerl", "footerr", "footerf");

    /** The text of an RTF file: control words are dropped, and paragraphs, tabs and characters kept. */
    static String rtf(byte[] content) {
        String rtf = new String(content, StandardCharsets.ISO_8859_1);
        StringBuilder text = new StringBuilder();
        Deque<boolean[]> groups = new ArrayDeque<>();
        boolean skipping = false;
        int unicodeSkip = 1;
        int pendingSkip = 0;
        int index = 0;
        while (index < rtf.length()) {
            char c = rtf.charAt(index);
            if (c == '{') {
                groups.push(new boolean[] {skipping});
                index++;
            } else if (c == '}') {
                skipping = groups.isEmpty() ? skipping : groups.pop()[0];
                index++;
            } else if (c == '\r' || c == '\n') {
                index++;
            } else if (c != '\\') {
                if (pendingSkip > 0) {
                    pendingSkip--;
                } else if (!skipping) {
                    text.append(c);
                }
                index++;
            } else if (index + 1 >= rtf.length()) {
                index++;
            } else {
                char next = rtf.charAt(index + 1);
                if (next == '\\' || next == '{' || next == '}') {
                    if (pendingSkip > 0) {
                        pendingSkip--;
                    } else if (!skipping) {
                        text.append(next);
                    }
                    index += 2;
                } else if (next == '\'') {
                    Integer value = index + 3 < rtf.length() ? hex(rtf.substring(index + 2, index + 4)) : null;
                    if (pendingSkip > 0) {
                        pendingSkip--;
                    } else if (!skipping && value != null) {
                        text.append(new String(new byte[] {value.byteValue()}, WINDOWS_1252));
                    }
                    index += 4;
                } else if (next == '*') {
                    skipping = true;
                    index += 2;
                } else if (next == '~') {
                    if (!skipping) {
                        text.append(' ');
                    }
                    index += 2;
                } else if (next == '_') {
                    if (!skipping) {
                        text.append('-');
                    }
                    index += 2;
                } else if (next == '\r' || next == '\n') {
                    if (!skipping) {
                        text.append('\n');
                    }
                    index += 2;
                } else if (Character.isLetter(next)) {
                    int end = index + 1;
                    while (end < rtf.length() && Character.isLetter(rtf.charAt(end))) {
                        end++;
                    }
                    String word = rtf.substring(index + 1, end);
                    int numberStart = end;
                    if (end + 1 < rtf.length() && rtf.charAt(end) == '-' && Character.isDigit(rtf.charAt(end + 1))) {
                        end++;
                    }
                    while (end < rtf.length() && Character.isDigit(rtf.charAt(end))) {
                        end++;
                    }
                    Integer number = end > numberStart ? number(rtf.substring(numberStart, end)) : null;
                    if (end < rtf.length() && rtf.charAt(end) == ' ') {
                        end++;
                    }
                    index = end;
                    if (RTF_SKIPPED.contains(word)) {
                        skipping = true;
                    } else if (word.equals("uc") && number != null) {
                        unicodeSkip = Math.max(0, number);
                    } else if (word.equals("u") && number != null) {
                        if (!skipping) {
                            text.append((char) (number < 0 ? number + 65_536 : number));
                        }
                        pendingSkip = unicodeSkip;
                    } else if (!skipping) {
                        switch (word) {
                            case "par", "line", "sect", "page", "row" -> text.append('\n');
                            case "tab", "cell" -> text.append('\t');
                            case "emdash", "endash" -> text.append(" - ");
                            case "bullet" -> text.append("- ");
                            case "lquote", "rquote" -> text.append('\'');
                            case "ldblquote", "rdblquote" -> text.append('"');
                            default -> {
                            }
                        }
                    }
                } else {
                    index += 2;
                }
            }
        }
        return text.toString();
    }

    private static Integer hex(String digits) {
        try {
            return Integer.parseInt(digits, 16);
        } catch (NumberFormatException notHex) {
            return null;
        }
    }

    private static Integer number(String digits) {
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException tooLong) {
            return null;
        }
    }

    // Text ------------------------------------------------------------------------------------

    static String txt(byte[] content) {
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xFE) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16LE);
        }
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFE && (content[1] & 0xFF) == 0xFF) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16BE);
        }
        int start = content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content, start, content.length - start))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            // Older Windows programs save text this way.
            return new String(content, start, content.length - start, WINDOWS_1252);
        }
    }

    // Shared ----------------------------------------------------------------------------------

    /** One line break between lines, a blank line between sections, single spaces. */
    static String tidy(String text) {
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace(' ', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\n\t]]", "")
                .replaceAll("[ \t -​]+", " ")
                .replaceAll(" *\n *", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    static boolean usefulLink(String link) {
        if (link == null || link.isBlank() || link.length() > 500) {
            return false;
        }
        String lower = link.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:");
    }

    /** The named parts of a zip file (a .docx or .odt), each up to a sane size. */
    private static Map<String, byte[]> unzip(byte[] content, Predicate<String> wanted) {
        Map<String, byte[]> parts = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null && ++entries <= MAX_ZIP_ENTRIES) {
                if (!entry.isDirectory() && wanted.test(entry.getName())) {
                    byte[] part = zip.readNBytes(MAX_PART_BYTES + 1);
                    if (part.length > MAX_PART_BYTES) {
                        throw new BadRequestException("This file is too large to read.");
                    }
                    parts.put(entry.getName(), part);
                }
            }
        } catch (IOException | IllegalArgumentException broken) {
            throw new BadRequestException("This file couldn't be opened. Try saving it again, or save it as a PDF.");
        }
        return parts;
    }

    /** What to do with each XML element and its text. */
    interface Visitor {
        /** Whether to leave out this element and everything in it. */
        default boolean skip(String name) {
            return false;
        }

        default void start(String name, String parent, XMLStreamReader reader) {
        }

        default void end(String name) {
        }

        /** Text directly inside the element named {@code inside}. */
        default void text(String inside, String value) {
        }
    }

    /** Reads XML with entities and DTDs off, so a file can't reach outside itself. */
    private static void walk(byte[] xml, Visitor visitor) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        Deque<String> open = new ArrayDeque<>();
        int skipped = 0;
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if (skipped > 0 || visitor.skip(name)) {
                        skipped++;
                        continue;
                    }
                    String parent = open.peek();
                    open.push(name);
                    visitor.start(name, parent, reader);
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (skipped > 0) {
                        skipped--;
                        continue;
                    }
                    visitor.end(open.isEmpty() ? reader.getLocalName() : open.pop());
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                        && skipped == 0 && !open.isEmpty()) {
                    visitor.text(open.peek(), reader.getText());
                }
            }
        } catch (XMLStreamException broken) {
            throw new BadRequestException("This file couldn't be read. Try saving it again, or save it as a PDF.");
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // Nothing left to read.
                }
            }
        }
    }
}
