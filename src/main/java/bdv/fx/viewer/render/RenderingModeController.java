package bdv.fx.viewer.render;

import java.lang.invoke.MethodHandles;

import org.apache.commons.lang.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RenderingModeController {

	private static Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	public static enum RenderingMode {
		MULTI_TILE,
		SINGLE_TILE
	}

	private static final int[] DEFAULT_TILE_SIZE = {250, 250};

	private final RenderUnit renderUnit;
	private RenderingMode mode;

	public RenderingModeController(final RenderUnit renderUnit, final RenderingMode mode)
	{
		this.renderUnit = renderUnit;
		setMode(mode);
	}

	public void setMode(final RenderingMode mode)
	{
		if (mode == this.mode)
			return;

		this.mode = mode;
		LOG.debug("Switching rendering mode to " + mode);
		switch (mode) {
		case MULTI_TILE:
			this.renderUnit.setBlockSize(DEFAULT_TILE_SIZE[0], DEFAULT_TILE_SIZE[1]);
			break;
		case SINGLE_TILE:
			this.renderUnit.setBlockSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
			break;
		default:
			throw new NotImplementedException("Rendering mode " + mode + " is not implemented yet");
		}
		this.renderUnit.requestRepaint();
	}
}
