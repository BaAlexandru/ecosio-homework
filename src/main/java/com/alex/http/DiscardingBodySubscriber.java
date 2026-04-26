package com.alex.http;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * BodySubscriber that cancels the response stream the moment it's offered,
 * so zero body bytes are pulled off the wire. Used for non-HTML responses
 * where we only need the status, content-type, and final URL.
 * <p>
 * Why not BodySubscribers.replacing(new byte[0]): that returns NullSubscriber,
 * which calls subscription.request(Long.MAX_VALUE) and drains the body before
 * completing same network cost as a full GET. (OpenJDK 21 source.)
 */
final class DiscardingBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private static final byte[] EMPTY = new byte[0];
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();

    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        subscription.cancel();           // RST_STREAM on h2; close on h1.1
        result.complete(EMPTY);          // unblock client.send()
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
    }

    @Override
    public void onError(Throwable t) {
        result.complete(EMPTY);
    }

    @Override
    public void onComplete() {
        result.complete(EMPTY);
    }
}