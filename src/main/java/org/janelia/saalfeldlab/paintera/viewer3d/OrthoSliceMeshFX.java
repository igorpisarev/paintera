package org.janelia.saalfeldlab.paintera.viewer3d;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;

import javafx.scene.shape.ObservableFaceArray;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;
import net.imglib2.RealInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealTransform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OrthoSliceMeshFX extends TriangleMesh
{

	public static Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private static final int[] indices = {
			0, 1, 2, 0, 2, 3
	};

	final int[] meshSize = new int[2];
	final float[] texCoordMin = new float[2], texCoordMax = new float[2];
	final float[] texCoords = new float[2 * 4];

	public OrthoSliceMeshFX(final RealInterval interval, final AffineTransform3D pointTransform)
	{
		Arrays.setAll(meshSize, d -> (int) Math.round(interval.realMax(d) - interval.realMin(d)));

		setVertices(
			new RealPoint(interval.realMin(0), interval.realMin(1)),
			new RealPoint(interval.realMax(0), interval.realMin(1)),
			new RealPoint(interval.realMax(0), interval.realMax(1)),
			new RealPoint(interval.realMin(0), interval.realMax(1)),
			pointTransform
		);

		setNormals(pointTransform);

		getTexCoords().setAll(texCoords); // initialize tex coords

		final ObservableFaceArray faceIndices = getFaces();
		for (final int i : indices)
			faceIndices.addAll(i, i, i);

		setVertexFormat(VertexFormat.POINT_NORMAL_TEXCOORD);
	}

	public void updateTexCoords(final int[] paddedTextureSize, final int[] padding, final double screenScale) {

		// When rendering at very low screen scales, the rendered data may overhang the border (incomplete) meshes.
		// In this case, compute the coordinate where the texture should be truncated to ensure proper scaling factors.
		for (int d = 0; d < 2; ++d) {
			final int unpaddedTextureSize = paddedTextureSize[d] - 2 * padding[d];
			final int upscaledTextureSize = (int) Math.max((int) unpaddedTextureSize / screenScale, meshSize[d]);
			final float meshSizeToUpscaledTextureSizeRatio = (float) meshSize[d] / upscaledTextureSize;
			final float unpaddedTextureCoord = (float) padding[d] / paddedTextureSize[d];
			texCoordMin[d] = unpaddedTextureCoord;
			texCoordMax[d] = unpaddedTextureCoord + meshSizeToUpscaledTextureSizeRatio * (1.0f - 2 * unpaddedTextureCoord);
		}

		texCoords[0] = texCoordMin[0]; texCoords[1] = texCoordMin[1];
		texCoords[2] = texCoordMax[0]; texCoords[3] = texCoordMin[1];
		texCoords[4] = texCoordMax[0]; texCoords[5] = texCoordMax[1];
		texCoords[6] = texCoordMin[0]; texCoords[7] = texCoordMax[1];

		getTexCoords().setAll(texCoords);
	}

	private void setVertices(
			final RealLocalizable bottomLeft,
			final RealLocalizable bottomRight,
			final RealLocalizable topRight,
			final RealLocalizable topLeft,
			final AffineTransform3D pointTransform)
	{
		final RealPoint p = new RealPoint(3);
		final double offset = 0.0;

		final float[] vertex = new float[3];
		final float[] vertexBuffer = new float[3 * 4];

		transformPoint(bottomLeft, p, pointTransform, offset);
		p.localize(vertex);
		System.arraycopy(vertex, 0, vertexBuffer, 0, 3);

		transformPoint(bottomRight, p, pointTransform, offset);
		p.localize(vertex);
		System.arraycopy(vertex, 0, vertexBuffer, 3, 3);

		transformPoint(topRight, p, pointTransform, offset);
		p.localize(vertex);
		System.arraycopy(vertex, 0, vertexBuffer, 6, 3);

		transformPoint(topLeft, p, pointTransform, offset);
		p.localize(vertex);
		System.arraycopy(vertex, 0, vertexBuffer, 9, 3);

		getPoints().setAll(vertexBuffer);
	}

	private void setNormals(final AffineTransform3D pointTransform)
	{
		final float[] normal = new float[] {0.0f, 0.0f, 1.0f};
		pointTransform.apply(normal, normal);
		final float norm = normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2];
		normal[0] /= norm;
		normal[1] /= norm;
		normal[2] /= norm;
		final float[] normalBuffer = new float[12];
		System.arraycopy(normal, 0, normalBuffer, 0, 3);
		System.arraycopy(normal, 0, normalBuffer, 3, 3);
		System.arraycopy(normal, 0, normalBuffer, 6, 3);
		System.arraycopy(normal, 0, normalBuffer, 9, 3);

		getNormals().setAll(normalBuffer);
	}

	private static <T extends RealPositionable & RealLocalizable> void transformPoint(
			final RealLocalizable source2D,
			final T target3D,
			final RealTransform transform,
			final double offset)
	{
		target3D.setPosition(source2D.getDoublePosition(0), 0);
		target3D.setPosition(source2D.getDoublePosition(1), 1);
		target3D.setPosition(offset, 2);
		transform.apply(target3D, target3D);
	}
}
