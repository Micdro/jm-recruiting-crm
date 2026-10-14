package com.janemichael.jmrecruitingcrm.candidate;

import com.janemichael.jmrecruitingcrm.auth.CurrentUserService;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.sync.SyncIdentity;
import com.janemichael.jmrecruitingcrm.trash.TrashKind;
import com.janemichael.jmrecruitingcrm.trash.TrashService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Resumes and other files on candidates. Contents are encrypted with the CRM key before they're
 * stored, and each file's encryption is tied to its own key, so they can only be read in the CRM.
 */
@Service
public class CandidateFileService {

    public static final int MAX_BYTES = 10 * 1024 * 1024;

    /** The kinds of file a candidate can have, and how each one starts. */
    enum Kind {
        PDF("pdf", "application/pdf", "%PDF".getBytes(StandardCharsets.US_ASCII), true),
        DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[] {'P', 'K', 3, 4}, false),
        DOC("doc", "application/msword", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}, false),
        ODT("odt", "application/vnd.oasis.opendocument.text", new byte[] {'P', 'K', 3, 4}, false),
        RTF("rtf", "application/rtf", "{\\rtf".getBytes(StandardCharsets.US_ASCII), false),
        TXT("txt", "text/plain", new byte[0], true);

        final String extension;
        final String contentType;
        final byte[] start;
        /** Opens in the browser rather than downloading. */
        final boolean inline;

        Kind(String extension, String contentType, byte[] start, boolean inline) {
            this.extension = extension;
            this.contentType = contentType;
            this.start = start;
            this.inline = inline;
        }

        static Kind forName(String fileName) {
            int dot = fileName.lastIndexOf('.');
            String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.extension.equals(extension)) {
                    return kind;
                }
            }
            return null;
        }

        static Kind forContentType(String contentType) {
            for (Kind kind : values()) {
                if (kind.contentType.equals(contentType)) {
                    return kind;
                }
            }
            return null;
        }

        boolean matches(byte[] content) {
            return content.length >= start.length && Arrays.equals(Arrays.copyOf(content, start.length), start);
        }
    }

    /**
     * @param available whether the contents are on this computer yet (a file added on another
     *                  computer arrives through the sync folder a little after its record)
     */
    public record CandidateFileResponse(
            Long id,
            Long candidateId,
            String fileName,
            String contentType,
            long sizeBytes,
            String uploadedByName,
            Instant createdAt,
            boolean available
    ) {
    }

    public record Download(String fileName, String contentType, boolean inline, byte[] content) {
    }

    private final CandidateFileRepository files;
    private final CandidateRepository candidates;
    private final CurrentUserService currentUser;
    private final FieldEncryptor encryptor;
    private final JdbcTemplate jdbc;
    private final SyncIdentity identity;
    private final TrashService trash;

    public CandidateFileService(
            CandidateFileRepository files,
            CandidateRepository candidates,
            CurrentUserService currentUser,
            FieldEncryptor encryptor,
            JdbcTemplate jdbc,
            SyncIdentity identity,
            TrashService trash
    ) {
        this.trash = trash;
        this.files = files;
        this.candidates = candidates;
        this.currentUser = currentUser;
        this.encryptor = encryptor;
        this.jdbc = jdbc;
        this.identity = identity;
    }

    @Transactional(readOnly = true)
    public List<CandidateFileResponse> list(Long candidateId) {
        requireCandidate(candidateId);
        List<CandidateFile> found = files.findForCandidate(candidateId);
        Set<String> here = new HashSet<>(jdbc.queryForList("""
                SELECT b.blob_key FROM file_blob b JOIN candidate_file f ON f.blob_key = b.blob_key
                WHERE f.candidate_id = ?
                """, String.class, candidateId));
        return found.stream().map(file -> response(file, here.contains(file.getBlobKey()))).toList();
    }

    @Transactional
    public CandidateFileResponse upload(Long candidateId, String rawName, InputStream body) {
        Candidate candidate = requireCandidate(candidateId);
        String fileName = cleanName(rawName);
        Kind kind = Kind.forName(fileName);
        if (kind == null) {
            throw new BadRequestException("Add a PDF, Word (.docx or .doc), OpenDocument (.odt), RTF or text file");
        }
        byte[] content;
        try {
            content = body.readNBytes(MAX_BYTES + 1);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read the file", exception);
        }
        if (content.length > MAX_BYTES) {
            throw new BadRequestException("Files can be up to 10 MB");
        }
        if (content.length == 0) {
            throw new BadRequestException("That file is empty");
        }
        if (!kind.matches(content)) {
            throw new BadRequestException("That file isn't a real ." + kind.extension + " file");
        }

        String blobKey = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO file_blob (blob_key, content) VALUES (?, ?)",
                blobKey, encryptor.encryptBytes(content, context(blobKey)));

        CandidateFile file = new CandidateFile();
        file.setCandidate(candidate);
        file.setFileName(fileName);
        file.setContentType(kind.contentType);
        file.setSizeBytes(content.length);
        file.setBlobKey(blobKey);
        file.setUploadedBy(currentUser.get());
        return response(files.save(file), true);
    }

    @Transactional(readOnly = true)
    public Download download(Long candidateId, Long fileId) {
        CandidateFile file = find(candidateId, fileId);
        List<byte[]> stored = jdbc.query("SELECT content FROM file_blob WHERE blob_key = ?",
                (resultSet, row) -> resultSet.getBytes(1), file.getBlobKey());
        if (stored.isEmpty()) {
            throw new ConflictException("This file hasn't reached this computer yet. It comes through the sync "
                    + "folder from the computer it was added on. Try again in a minute.");
        }
        Kind kind = Kind.forContentType(file.getContentType());
        return new Download(file.getFileName(), file.getContentType(), kind != null && kind.inline,
                encryptor.decryptBytes(stored.getFirst(), context(file.getBlobKey())));
    }

    /**
     * Removes the file's record. With sync, the contents are removed here and from the sync folder
     * on the next round, once no record needs them; without it, right away.
     */
    @Transactional
    public void delete(Long candidateId, Long fileId) {
        CandidateFile file = find(candidateId, fileId);
        trash.capture(TrashKind.FILE, file.getId());
        files.delete(file);
        files.flush();
        if (!identity.isEnabled()) {
            removeUnused(false);
        }
    }

    /** Without sync, clears out contents whose records are gone (a deleted candidate takes its files). */
    @Scheduled(initialDelayString = "${app.files.cleanup-initial-delay-ms:600000}",
            fixedDelayString = "${app.files.cleanup-interval-ms:3600000}")
    public void cleanUp() {
        if (!identity.isEnabled()) {
            removeUnused(true);
        }
    }

    private void removeUnused(boolean onlyOld) {
        jdbc.update("""
                DELETE FROM file_blob b
                WHERE NOT EXISTS (SELECT 1 FROM candidate_file f WHERE f.blob_key = b.blob_key)
                  AND NOT EXISTS (SELECT 1 FROM trash_item t WHERE position(b.blob_key IN coalesce(t.blob_keys, '')) > 0)
                """ + (onlyOld ? " AND b.created_at < now() - interval '5 minutes'" : ""));
    }

    /** What each file's encryption is tied to. */
    public static String context(String blobKey) {
        return "candidate file " + blobKey;
    }

    static String cleanName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (name.isEmpty()) {
            throw new BadRequestException("The file needs a name");
        }
        if (name.length() > 200) {
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot);
            name = name.substring(0, 200 - extension.length()) + extension;
        }
        return name;
    }

    private Candidate requireCandidate(Long candidateId) {
        return candidates.findById(candidateId).orElseThrow(() -> new NotFoundException("Candidate not found"));
    }

    private CandidateFile find(Long candidateId, Long fileId) {
        return files.findForCandidate(candidateId, fileId).orElseThrow(() -> new NotFoundException("File not found"));
    }

    private static CandidateFileResponse response(CandidateFile file, boolean available) {
        return new CandidateFileResponse(file.getId(), file.getCandidate().getId(), file.getFileName(),
                file.getContentType(), file.getSizeBytes(),
                file.getUploadedBy() == null ? null : file.getUploadedBy().getDisplayName(),
                file.getCreatedAt(), available);
    }
}
