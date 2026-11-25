package app.arche.nas;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

/** Corps de requête lu directement depuis le fichier du téléphone (rien n'est copié en mémoire). */
final class StreamBody extends RequestBody {
    private static final MediaType OCTET = MediaType.parse("application/octet-stream");
    private final ContentResolver cr; private final Uri uri; private final long offset, length;

    StreamBody(ContentResolver cr, Uri uri, long offset, long length) { this.cr = cr; this.uri = uri; this.offset = offset; this.length = length; }

    @Override public MediaType contentType() { return OCTET; }
    @Override public long contentLength() { return length; }

    @Override public void writeTo(BufferedSink sink) throws IOException {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("fichier illisible");
            long skip = offset;
            while (skip > 0) { long s = in.skip(skip); if (s <= 0) { if (in.read() < 0) throw new IOException("fichier raccourci"); s = 1; } skip -= s; }
            byte[] buf = new byte[256 * 1024];
            long left = length;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("fichier raccourci");
                sink.write(buf, 0, n);
                left -= n;
            }
        }
    }
}
