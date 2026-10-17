package com.janemichael.jmrecruitingcrm.marketing;

import com.janemichael.jmrecruitingcrm.ai.AiPicture;
import com.janemichael.jmrecruitingcrm.ai.AiService;
import com.janemichael.jmrecruitingcrm.common.BadRequestException;
import com.janemichael.jmrecruitingcrm.common.ConflictException;
import com.janemichael.jmrecruitingcrm.common.FieldEncryptor;
import com.janemichael.jmrecruitingcrm.common.NotFoundException;
import com.janemichael.jmrecruitingcrm.common.Text;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PictureRequest;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PictureResponse;
import com.janemichael.jmrecruitingcrm.marketing.MarketingDtos.PostResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A post's picture: uploaded, or made by AI. The file is kept in file_blob like candidates' files,
 * encrypted with the CRM key, and reaches the other computers through the sync folder. A replaced
 * or removed picture's file is cleared out with the other files no record uses.
 */
@Service
public class PostPictures {

    /** LinkedIn takes larger pictures, but a feed picture this big is plenty. */
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    static final int BODY_SHOWN = 600;
    static final String DEFAULT_STYLE = "Clean, modern and professional, with calm navy and gold colors. An editorial "
            + "illustration or a photo-like scene.";

    /** The kinds of picture LinkedIn shows in a post, and how each file starts. */
    enum Kind {
        JPEG("image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
        PNG("image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'}),
        GIF("image/gif", "GIF8".getBytes(StandardCharsets.US_ASCII)),
        WEBP("image/webp", "RIFF".getBytes(StandardCharsets.US_ASCII));

        final String contentType;
        final byte[] start;

        Kind(String contentType, byte[] start) {
            this.contentType = contentType;
            this.start = start;
        }

        static Kind of(byte[] content) {
            for (Kind kind : values()) {
                if (content.length >= 12 && Arrays.equals(Arrays.copyOf(content, kind.start.length), kind.start)
                        && (kind != WEBP || "WEBP".equals(new String(content, 8, 4, StandardCharsets.US_ASCII)))) {
                    return kind;
                }
            }
            return null;
        }
    }

    public record Picture(String contentType, byte[] content) {
    }

    private final MarketingService marketing;
    private final ContentPlan plan;
    private final AiService ai;
    private final FieldEncryptor encryptor;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PostPictures(MarketingService marketing, ContentPlan plan, AiService ai, FieldEncryptor encryptor,
                        JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.marketing = marketing;
        this.plan = plan;
        this.ai = ai;
        this.encryptor = encryptor;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public PostResponse upload(Long postId, InputStream body) {
        byte[] content;
        try {
            content = body.readNBytes(MAX_BYTES + 1);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read the picture", exception);
        }
        if (content.length > MAX_BYTES) {
            throw new BadRequestException("Pictures can be up to 8 MB");
        }
        Kind kind = Kind.of(content);
        if (kind == null) {
            throw new BadRequestException("Add a JPG, PNG, GIF or WebP picture");
        }
        return save(postId, content, kind.contentType, "UPLOAD");
    }

    /** Has AI make a picture for the post from its title and words, in the plan's picture style. */
    public PictureResponse generate(Long postId, PictureRequest request) {
        String prompt = transaction.execute(status -> {
            ContentPost post = marketing.find(postId);
            ContentPlan.Values rules = plan.values(
                    YearMonth.from(post.getPlannedFor() == null ? LocalDate.now(clock) : post.getPlannedFor()));
            return prompt(post.getTitle(), post.getBody() == null ? post.getNotes() : post.getBody(),
                    rules.pictureStyle(), request == null ? null : Text.clean(request.direction()));
        });
        AiPicture picture = ai.picture("Making a picture", prompt);
        return new PictureResponse(save(postId, picture.content(), picture.contentType(), "AI"), picture.costCents());
    }

    public PostResponse remove(Long postId) {
        return transaction.execute(status -> {
            ContentPost post = marketing.find(postId);
            post.setImage(null, null, null);
            return marketing.response(post);
        });
    }

    public Picture read(Long postId) {
        return transaction.execute(status -> {
            ContentPost post = marketing.find(postId);
            if (post.getImageKey() == null) {
                throw new NotFoundException("This post has no picture");
            }
            List<byte[]> stored = jdbc.query("SELECT content FROM file_blob WHERE blob_key = ?",
                    (resultSet, row) -> resultSet.getBytes(1), post.getImageKey());
            if (stored.isEmpty()) {
                throw new ConflictException("This picture hasn't reached this computer yet. It comes through the "
                        + "sync folder from the computer it was added on. Try again in a minute.");
            }
            return new Picture(post.getImageType(),
                    encryptor.decryptBytes(stored.getFirst(), context(post.getImageKey())));
        });
    }

    private PostResponse save(Long postId, byte[] content, String contentType, String source) {
        return transaction.execute(status -> {
            ContentPost post = marketing.find(postId);
            String key = UUID.randomUUID().toString().replace("-", "");
            jdbc.update("INSERT INTO file_blob (blob_key, content) VALUES (?, ?)",
                    key, encryptor.encryptBytes(content, context(key)));
            post.setImage(key, contentType, source);
            return marketing.response(post);
        });
    }

    static String prompt(String title, String words, String style, String direction) {
        StringBuilder text = new StringBuilder();
        text.append("A picture to go with a LinkedIn post from Jane Michael AI Talent Search, a recruiting firm that ")
                .append("places engineers with AI and cybersecurity companies that serve banks and insurers.\n\n");
        text.append("Picture style: ").append(style == null || style.isBlank() ? DEFAULT_STYLE : style.trim()).append('\n');
        text.append("What the post is about: ").append(title.trim()).append('\n');
        if (words != null && !words.isBlank()) {
            String shown = words.trim();
            text.append("The post: ").append(shown.length() > BODY_SHOWN ? shown.substring(0, BODY_SHOWN) + "…" : shown)
                    .append('\n');
        }
        if (direction != null && !direction.isBlank()) {
            text.append("Direction: ").append(direction.trim()).append('\n');
        }
        text.append("\nRules: no words, letters, numbers or logos anywhere in the picture. No real, recognizable ")
                .append("people. Nothing that points to a particular company, client or candidate. Landscape.");
        return text.toString();
    }

    /** What each picture's encryption is tied to. */
    static String context(String blobKey) {
        return "post picture " + blobKey;
    }
}
