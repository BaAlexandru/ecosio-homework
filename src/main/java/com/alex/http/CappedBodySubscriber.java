package com.alex.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Streaming body subscriber that aborts the response once {@code cap} bytes
 * have been accumulated. Implements {@link BodySubscriber} directly so size
 * enforcement happens during {@code onNext} not after the body is buffered.
 */
final class CappedBodySubscriber implements BodySubscriber<byte[]> {

    private final long cap;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private long bytesSeen;
    private Flow.Subscription subscription;

    CappedBodySubscriber(long cap) {
        this.cap = cap;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> chunks) {
        for (ByteBuffer chunk : chunks) {
            int remaining = chunk.remaining();
            bytesSeen += remaining;
            if (bytesSeen > cap) {
                subscription.cancel();
                result.completeExceptionally(new IOException(
                        "response exceeded size cap of " + cap + " bytes"));
                return;
            }
            byte[] copy = new byte[remaining];
            chunk.get(copy);
            buffer.write(copy, 0, copy.length);
        }
    }

    @Override
    public void onError(Throwable t) {
        result.completeExceptionally(t);
    }

    @Override
    public void onComplete() {
        result.complete(buffer.toByteArray());
    }
}