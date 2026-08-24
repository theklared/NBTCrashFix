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
	private static final Logger NCF$LOGGER = LoggerFactory.getLogger("nbtcrashfix");

	@Unique
	private static final int NCF$RATELIMIT_THRESHOLD = 15; // 15/s
	@Unique
	private static final long NCF$RATELIMIT_RESET_MILLIS = 10_000L;
	@Unique
	private static final long NCF$WINDOW_MILLIS = 1000L;
	@Unique
	private static final long NCF$LOG_INTERVAL_MILLIS = 5000L;

	@Unique
	private final DiscardRateLimiter NCF$rateLimiter = new DiscardRateLimiter();

	@Unique
	private static void NCF$log(String msg, Object... args) {
		NCF$LOGGER.warn("[nbtcrashfix] " + msg, args);
	}

	@Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
	private void NCF$suppressDecodeCrash(ChannelHandlerContext ctx, Throwable cause, CallbackInfo ci) {
		if (!NCF$isSuppressibleDecodeFailure(cause))
			return;

		long now = System.currentTimeMillis();
		NCF$rateLimiter.totalDiscardCount++;

		// check if should disable ratelimit
		if (NCF$rateLimiter.rateLimited && now - NCF$rateLimiter.lastDiscardTime > NCF$RATELIMIT_RESET_MILLIS)
			NCF$rateLimiter.rateLimited = false;
		NCF$rateLimiter.lastDiscardTime = now;

		// check if should enable ratelimit
		if (now - NCF$rateLimiter.windowStart > NCF$WINDOW_MILLIS) {
			NCF$rateLimiter.windowStart = now;
			NCF$rateLimiter.discardCount = 0;
		}
		NCF$rateLimiter.discardCount++;

		if (!NCF$rateLimiter.rateLimited && NCF$rateLimiter.discardCount > NCF$RATELIMIT_THRESHOLD) {
			NCF$rateLimiter.rateLimited = true;
			NCF$rateLimiter.lastLogTime = now;
			NCF$log("Discard rate limit exceeded ({} in {}ms): {}", NCF$rateLimiter.discardCount, NCF$WINDOW_MILLIS, cause.getMessage());
		}

		// log malformed packet
		if (!NCF$rateLimiter.rateLimited)
			NCF$log("(#{}) Discarded malformed packet: {}", NCF$rateLimiter.totalDiscardCount, cause.getMessage());
		else {
			NCF$rateLimiter.suppressedSinceLastLog++;
			if (now - NCF$rateLimiter.lastLogTime > NCF$LOG_INTERVAL_MILLIS) {
				NCF$log("(#{}) Discarded {} malformed packet(s) in ~{}ms: {}",
						NCF$rateLimiter.totalDiscardCount,
						NCF$rateLimiter.suppressedSinceLastLog,
						NCF$LOG_INTERVAL_MILLIS,
						cause.getMessage());
				NCF$rateLimiter.lastLogTime = now;
				NCF$rateLimiter.suppressedSinceLastLog = 0;
			}
		}

		NCF$resetDecoderCumulation(ctx);
		ci.cancel();
	}

	/**
	 * Forcibly clears the decoder's internal cumulation buffer so the next
	 * incoming bytes are read freshly instead of continuing from a
	 * reader position corrupted by the packet we just discarded, avoiding desync.
	 */
	@Unique
	private static void NCF$resetDecoderCumulation(ChannelHandlerContext ctx) {
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
					NCF$log("Discarding {} unread bytes along with malformed packet", bb.readableBytes());
				bb.release();
			}

			cumulationField.set(decoder, null);
			Field firstField = ByteToMessageDecoder.class.getDeclaredField("first");
			firstField.setAccessible(true);
			firstField.setBoolean(decoder, true);
		} catch (Exception e) {
			NCF$LOGGER.error("[nbtcrashfix] Failed to reset decoder cumulation: {}", String.valueOf(e));
		}
	}

	@Unique
	private static boolean NCF$isSuppressibleDecodeFailure(Throwable t) {
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
