package net.mll.nbtcrashfix.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.Connection;
import net.mll.nbtcrashfix.util.DiscardRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.lang.reflect.Field;

@Mixin(Connection.class)
public class ConnectionExceptionMixin {
	@Unique
	private static final Logger LOGGER = LoggerFactory.getLogger("nbtcrashfix");

	@Unique
	private static final int RATELIMIT_THRESHOLD = 15; // 15/s
	@Unique
	private static final long RATELIMIT_RESET_MILLIS = 10_000L;
	@Unique
	private static final long WINDOW_MILLIS = 1000L;
	@Unique
	private static final long LOG_INTERVAL_MILLIS = 5000L;

	@Unique
	private final DiscardRateLimiter rateLimiter = new DiscardRateLimiter();

	@Unique
	private static void log(String msg, Object... args) {
		LOGGER.warn("[nbtcrashfix] " + msg, args);
	}

	@Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
	private void suppressDecodeCrash(ChannelHandlerContext ctx, Throwable cause, CallbackInfo ci) {
		if (!isSuppressibleDecodeFailure(cause))
			return;

		long now = System.currentTimeMillis();
		rateLimiter.totalDiscardCount++;

		// check if should disable ratelimit
		if (rateLimiter.rateLimited && now - rateLimiter.lastDiscardTime > RATELIMIT_RESET_MILLIS)
			rateLimiter.rateLimited = false;
		rateLimiter.lastDiscardTime = now;

		// check if should enable ratelimit
		if (now - rateLimiter.windowStart > WINDOW_MILLIS) {
			rateLimiter.windowStart = now;
			rateLimiter.discardCount = 0;
		}
		rateLimiter.discardCount++;

		if (!rateLimiter.rateLimited && rateLimiter.discardCount > RATELIMIT_THRESHOLD) {
			rateLimiter.rateLimited = true;
			rateLimiter.lastLogTime = now;
			log("Discard rate limit exceeded ({} in {}ms): {}", rateLimiter.discardCount, WINDOW_MILLIS, cause.getMessage());
		}

		// log malformed packet
		if (!rateLimiter.rateLimited)
			log("(#{}) Discarded malformed packet: {}", rateLimiter.totalDiscardCount, cause.getMessage());
		else {
			rateLimiter.suppressedSinceLastLog++;
			if (now - rateLimiter.lastLogTime > LOG_INTERVAL_MILLIS) {
				log("(#{}) Discarded {} malformed packet(s) in ~{}ms: {}",
						rateLimiter.totalDiscardCount,
						rateLimiter.suppressedSinceLastLog,
						LOG_INTERVAL_MILLIS,
						cause.getMessage());
				rateLimiter.lastLogTime = now;
				rateLimiter.suppressedSinceLastLog = 0;
			}
		}

		resetDecoderCumulation(ctx);
		ci.cancel();
	}

	/**
	 * Forcibly clears the decoder's internal cumulation buffer so the next
	 * incoming bytes are read freshly instead of continuing from a
	 * reader position corrupted by the packet we just discarded, avoiding desync.
	 */
	@Unique
	private static void resetDecoderCumulation(ChannelHandlerContext ctx) {
		ChannelHandler handler = ctx.pipeline().get("decoder");
		if (!(handler instanceof ByteToMessageDecoder decoder))
			return;

		try {
			Field cumulationField = ByteToMessageDecoder.class.getDeclaredField("cumulation");
			cumulationField.setAccessible(true);
			Object buf = cumulationField.get(decoder);

			if (buf instanceof ByteBuf bb && bb.refCnt() > 0) {
				// We could lose a few packets if delivered in the same ByteBuf.
				// It's easier to accept the loss rather than tracking exactly which is malformed.
				// Client will be corrected next server update anyway :shrug:
				if (bb.isReadable())
					log("Discarding {} unread bytes along with malformed packet", bb.readableBytes());
				bb.release();
			}

			cumulationField.set(decoder, null);
			Field firstField = ByteToMessageDecoder.class.getDeclaredField("first");
			firstField.setAccessible(true);
			firstField.setBoolean(decoder, true);
		} catch (Exception e) {
            LOGGER.error("[nbtcrashfix] Failed to reset decoder cumulation: {}", String.valueOf(e));
		}
	}

	@Unique
	private static boolean isSuppressibleDecodeFailure(Throwable t) {
		for (Throwable cur = t; cur != null; cur = cur.getCause()) {
			// this is kinda shit
			boolean isDecodeIssue = cur instanceof DecoderException
				|| (cur instanceof IOException && cur.getMessage() != null
				&& cur.getMessage().contains("was larger than I expected"));

			if (!isDecodeIssue)
				continue;
            return cur.getMessage() != null;
        }
		return false;
	}
}
