package com.janemichael.jmrecruitingcrm.candidate;

import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeTextTest {

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String LINKEDIN = "https://www.linkedin.com/in/ada-example";

    @Test
    void readsAPdfAndTheLinksBehindItsWords() throws IOException {
        String text = ResumeText.read("ada.pdf", pdf(List.of("Ada Lovelace", "Staff ML Engineer at Example Bank",
                "Owned fraud scoring in production"), LINKEDIN));

        assertThat(text).contains("Ada Lovelace").contains("Staff ML Engineer at Example Bank")
                .endsWith("Links in the file:\n" + LINKEDIN);
    }

    @Test
    void saysWhenAPdfIsAScanWithNoText() throws IOException {
        assertThatThrownBy(() -> ResumeText.read("scan.pdf", pdf(List.of(), null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("scanned picture");
    }

    @Test
    void readsAWordFileWithItsHeaderTextBoxOnceAndLinks() throws IOException {
        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="%s" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                    xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">
                <w:body>
                <w:p><w:pPr><w:tabs><w:tab w:val="left" w:pos="720"/></w:tabs></w:pPr>
                  <w:r><w:t>Staff ML Engineer</w:t></w:r><w:r><w:tab/><w:t>2019</w:t><w:noBreakHyphen/><w:t>2024</w:t></w:r></w:p>
                <w:p><w:r><mc:AlternateContent>
                  <mc:Choice Requires="wps"><w:drawing><w:txbxContent><w:p><w:r><w:t>Owned fraud scoring</w:t></w:r></w:p></w:txbxContent></w:drawing></mc:Choice>
                  <mc:Fallback><w:pict><w:txbxContent><w:p><w:r><w:t>Owned fraud scoring</w:t></w:r></w:p></w:txbxContent></w:pict></mc:Fallback>
                </mc:AlternateContent></w:r></w:p>
                <w:p><w:hyperlink r:id="rId5"><w:r><w:t>LinkedIn</w:t></w:r></w:hyperlink></w:p>
                <w:p><w:r><w:delText>Words taken out</w:delText></w:r></w:p>
                </w:body></w:document>
                """.formatted(W);
        byte[] docx = zip(Map.of(
                "word/document.xml", document,
                "word/header1.xml", "<w:hdr xmlns:w=\"" + W + "\"><w:p><w:r><w:t>Ada Lovelace</w:t></w:r></w:p>"
                        + "<w:p><w:r><w:t>ada@example.com</w:t></w:r></w:p></w:hdr>",
                "word/_rels/document.xml.rels", """
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                          <Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="%s" TargetMode="External"/>
                        </Relationships>
                        """.formatted(LINKEDIN)));

        String text = ResumeText.read("ada.docx", docx);

        assertThat(text).startsWith("Ada Lovelace\nada@example.com\n")
                .contains("Staff ML Engineer 2019-2024")
                .contains("LinkedIn")
                .doesNotContain("Words taken out")
                .doesNotContain("styles.xml")
                .endsWith("Links in the file:\n" + LINKEDIN);
        assertThat(text.split("Owned fraud scoring", -1)).hasSize(2);
    }

    @Test
    void neverReadsOutsideTheFile(@TempDir Path folder) throws IOException {
        Path secret = folder.resolve("secret.txt");
        Files.writeString(secret, "TOP SECRET CONTENTS");
        String document = """
                <?xml version="1.0"?>
                <!DOCTYPE w:document [<!ENTITY secret SYSTEM "%s">]>
                <w:document xmlns:w="%s"><w:body><w:p><w:r><w:t>Ada Lovelace, Staff ML Engineer, &secret;</w:t></w:r></w:p>
                </w:body></w:document>
                """.formatted(secret.toUri(), W);
        byte[] docx = zip(Map.of("word/document.xml", document));

        try {
            assertThat(ResumeText.read("ada.docx", docx)).doesNotContain("TOP SECRET");
        } catch (BadRequestException refused) {
            assertThat(refused).hasMessageContaining("couldn't be read");
        }
    }

    @Test
    void readsAnOpenDocumentFileWithoutItsComments() throws IOException {
        byte[] odt = zip(Map.of("content.xml", """
                <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:xlink="http://www.w3.org/1999/xlink">
                <office:body><office:text>
                <text:h>Ada Lovelace</text:h>
                <text:p>Staff<text:s/>ML Engineer<text:tab/>Example Bank<office:annotation><text:p>Check her dates</text:p></office:annotation></text:p>
                <text:p><text:a xlink:href="%s">LinkedIn</text:a></text:p>
                </office:text></office:body></office:document-content>
                """.formatted(LINKEDIN)));

        String text = ResumeText.read("ada.odt", odt);

        assertThat(text).startsWith("Ada Lovelace\nStaff ML Engineer Example Bank\nLinkedIn")
                .doesNotContain("Check her dates")
                .endsWith("Links in the file:\n" + LINKEDIN);
    }

    @Test
    void readsAnRtfFile() {
        String rtf = "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Arial;}}{\\colortbl;\\red0\\green0\\blue0;}"
                + "{\\*\\generator Writer;}\n\\f0 Ada Lovelace\\par\nCaf\\'e9 owner\\tab 2019\\par\n"
                + "{\\*\\fldinst HYPERLINK \"x\"}Smart \\u8220?quotes\\u8221?\\par\n}";

        String text = ResumeText.read("ada.rtf", rtf.getBytes(StandardCharsets.US_ASCII));

        assertThat(text).isEqualTo("Ada Lovelace\nCafé owner 2019\nSmart “quotes”");
    }

    @Test
    void readsTextSavedByOlderWindowsPrograms() {
        byte[] text = "José García, Staff ML Engineer, Example Bank, Austin TX"
                .getBytes(Charset.forName("windows-1252"));

        assertThat(ResumeText.read("jose.txt", text)).isEqualTo("José García, Staff ML Engineer, Example Bank, Austin TX");
    }

    @Test
    void explainsFilesItCantRead() {
        assertThatThrownBy(() -> ResumeText.read("old.doc", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 1}))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("save it as .docx or PDF");
        assertThatThrownBy(() -> ResumeText.read("ada.pdf", "Not a PDF at all".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("isn't a real .pdf file");
        assertThatThrownBy(() -> ResumeText.read("ada.png", new byte[] {1, 2, 3}))
                .hasMessageContaining("PDF, Word (.docx)");
        assertThatThrownBy(() -> ResumeText.read("short.txt", "Ada".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("no text in this file");
    }

    @Test
    void tidiesSpacingAndKeepsOnlyWebAndEmailLinks() {
        assertThat(ResumeText.tidy("Ada  Lovelace  \r\n\r\n\r\n\tStaff\u0007 engineer ")).isEqualTo("Ada Lovelace\n\nStaff engineer");
        assertThat(ResumeText.usefulLink("mailto:ada@example.com")).isTrue();
        assertThat(ResumeText.usefulLink("javascript:alert(1)")).isFalse();
        assertThat(ResumeText.usefulLink("#_Toc123")).isFalse();
    }

    private static byte[] pdf(List<String> lines, String link) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            if (!lines.isEmpty()) {
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    for (String line : lines) {
                        content.showText(line);
                        content.newLineAtOffset(0, -16);
                    }
                    content.endText();
                }
            }
            if (link != null) {
                PDActionURI action = new PDActionURI();
                action.setURI(link);
                PDAnnotationLink annotation = new PDAnnotationLink();
                annotation.setAction(action);
                annotation.setRectangle(new PDRectangle(72, 600, 100, 14));
                page.setAnnotations(List.of(annotation));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
