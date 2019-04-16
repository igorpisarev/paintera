package org.janelia.saalfeldlab.paintera.data.n5;

import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.TLongHashSet;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.util.Grids;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.ReadOnlyCachedCellImgFactory;
import net.imglib2.cache.img.ReadOnlyCachedCellImgOptions;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.label.Label;
import net.imglib2.type.label.LabelMultisetType;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.labels.blocks.LabelBlockLookupFromFile;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.LongArrayDataBlock;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5LabelMultisets;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.paintera.data.mask.persist.PersistCanvas;
import org.janelia.saalfeldlab.paintera.data.mask.persist.UnableToPersistCanvas;
import org.janelia.saalfeldlab.paintera.data.mask.persist.UnableToUpdateLabelBlockLookup;
import org.janelia.saalfeldlab.util.n5.N5Helpers;
import org.janelia.saalfeldlab.util.n5.N5TestUtil;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.touk.throwing.ThrowingBiConsumer;
import pl.touk.throwing.ThrowingBiFunction;
import pl.touk.throwing.ThrowingConsumer;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class CommitCanvasN5Test {

	private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private static final UnsignedLongType INVALID = new UnsignedLongType(Label.INVALID);

	private static final Map<String, Object> MULTISET_ATTRIBUTE = Collections.unmodifiableMap(Stream.of(1).collect(Collectors.toMap(o -> N5Helpers.LABEL_MULTISETTYPE_KEY, o -> true)));

	private static final Map<String, Object> PAINTERA_DATA_ATTRIBUTE = Collections.unmodifiableMap(Stream.of(1).collect(Collectors.toMap(o -> "type", o -> "label")));

	private static boolean isInvalid(final UnsignedLongType pixel) {
		final boolean isInvalid = INVALID.valueEquals(pixel);
		LOG.trace("{} is invalid? {}", pixel, isInvalid);
		return isInvalid;
	}

	@Test
	public void testCommit() throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup, ReflectionException {

		final long[] dims = new long[] {10, 20, 30};
		final int[] blockSize = new int[] {5, 7, 9};
		final CellGrid grid = new CellGrid(dims, blockSize);
		final CellLoader<UnsignedLongType> loader = img -> img.forEach(UnsignedLongType::setOne);
		final ReadOnlyCachedCellImgFactory factory = new ReadOnlyCachedCellImgFactory(ReadOnlyCachedCellImgOptions.options().cellDimensions(blockSize));
		final CachedCellImg<UnsignedLongType, ?> canvas = factory.create(dims, new UnsignedLongType(), loader);
		final Random rng = new Random(100);
		canvas.forEach(px -> px.setInteger(rng.nextDouble() > 0.5 ? rng.nextInt(50) : Label.INVALID));
		final int[][] scales = {{2, 2, 3}};

		final N5FSWriter container = N5TestUtil.fileSystemWriterAtTmpDir(!LOG.isDebugEnabled());
		LOG.debug("Created temporary N5 container {}", container);
		testLabelMultisetSingleScale(container, "single-scale-label-multisets", canvas);
		testLabelMultisetMultiScale(container, "multi-scale-label-multisets", canvas);
		testLabelMultisetPaintera(container, "paintera-label-multisets", canvas, scales);
		testUnsignedLongTypeSingleScale(container, "single-scale-uint64", canvas);
		testUnsignedLongTypeMultiScale(container, "multi-scale-uint64", canvas);
		testUnsignedLongTypePaintera(container, "paintera-uint64", canvas, scales);
	}

	private static void assertMultisetType(final UnsignedLongType c, final LabelMultisetType l) {
		Assert.assertEquals(1, l.entrySet().size());
		final LabelMultisetType.Entry<Label> entry = l.entrySet().iterator().next();
		Assert.assertEquals(1, entry.getCount());
		final boolean isInvalid = isInvalid(c);
		if (isInvalid) {
			Assert.assertEquals(0, l.getIntegerLong());
		} else {
			Assert.assertEquals(c.getIntegerLong(), entry.getElement().id());
		}
	}

	private static void testUnsignedLongTypeSingleScale(
			final N5FSWriter container,
			final String dataset,
			final CachedCellImg<UnsignedLongType, ?> canvas) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup {
		CommitCanvasN5Test.<UnsignedLongType>testSingleScale(
				container,
				dataset,
				DataType.UINT64,
				canvas,
				ThrowingBiFunction.unchecked(N5Utils::open),
				(c, l) -> Assert.assertEquals(isInvalid(c) ? 0 : c.getIntegerLong(), l.getIntegerLong()));
	}

	private static void testLabelMultisetSingleScale(
			final N5FSWriter container,
			final String dataset,
			final CachedCellImg<UnsignedLongType, ?> canvas) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup {
		CommitCanvasN5Test.testSingleScale(
				container,
				dataset,
				DataType.UINT8,
				canvas,
				ThrowingBiFunction.unchecked(N5LabelMultisets::openLabelMultiset),
				CommitCanvasN5Test::assertMultisetType,
				MULTISET_ATTRIBUTE);
	}

	private static void testUnsignedLongTypeMultiScale(
			final N5FSWriter container,
			final String dataset,
			final CachedCellImg<UnsignedLongType, ?> canvas) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup {
		CommitCanvasN5Test.<UnsignedLongType>testMultiScale(
				container,
				dataset,
				DataType.UINT64,
				canvas,
				ThrowingBiFunction.unchecked(N5Utils::open),
				(c, l) -> Assert.assertEquals(isInvalid(c) ? 0 : c.getIntegerLong(), l.getIntegerLong()));
	}

	private static void testLabelMultisetMultiScale(
			final N5FSWriter container,
			final String dataset,
			final CachedCellImg<UnsignedLongType, ?> canvas) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup {
		CommitCanvasN5Test.testMultiScale(
				container,
				dataset,
				DataType.UINT8,
				canvas,
				ThrowingBiFunction.unchecked(N5LabelMultisets::openLabelMultiset),
				CommitCanvasN5Test::assertMultisetType,
				MULTISET_ATTRIBUTE);
	}

	private static void testUnsignedLongTypePaintera(
			final N5FSWriter container,
			final String dataset,
			final CachedCellImg<UnsignedLongType, ?> canvas,
			final int[]... scales) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup, ReflectionException {
		CommitCanvasN5Test.<UnsignedLongType>testPainteraData(
				container,
				dataset,
				DataType.UINT64,
				canvas,
				ThrowingBiFunction.unchecked(N5Utils::open),
				(c, l) -> Assert.assertEquals(isInvalid(c) ? 0 : c.getIntegerLong(), l.getIntegerLong()),
				scales);
	}

	private static void testLabelMultisetPaintera(
			final N5FSWriter container,
			final String dataset,
			final CachedCellImg<UnsignedLongType, ?> canvas,
			final int[]... scales) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup, ReflectionException {
		CommitCanvasN5Test.testPainteraData(
				container,
				dataset,
				DataType.UINT8,
				canvas,
				ThrowingBiFunction.unchecked(N5LabelMultisets::openLabelMultiset),
				CommitCanvasN5Test::assertMultisetType,
				MULTISET_ATTRIBUTE,
				scales);
	}

	private static <T> void testPainteraData(
			final N5FSWriter container,
			final String dataset,
			final DataType dataType,
			final CachedCellImg<UnsignedLongType, ?> canvas,
			final BiFunction<N5Reader, String, RandomAccessibleInterval<T>> openLabels,
			final BiConsumer<UnsignedLongType, T> asserts,
			final int[]... scaleFactors
	) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup, ReflectionException {
		testPainteraData(container, dataset, dataType, canvas, openLabels, asserts, new HashMap<>(), scaleFactors);
	}

	private static <T> void testPainteraData(
			final N5FSWriter container,
			final String dataset,
			final DataType dataType,
			final CachedCellImg<UnsignedLongType, ?> canvas,
			final BiFunction<N5Reader, String, RandomAccessibleInterval<T>> openLabels,
			final BiConsumer<UnsignedLongType, T> asserts,
			final Map<String, Object> additionalAttributes,
			final int[]... scaleFactors
	) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup, ReflectionException {

		final int[] blockSize = blockSize(canvas.getCellGrid());
		final long[] dims = canvas.getCellGrid().getImgDimensions();
		final DatasetAttributes attributes = new DatasetAttributes(dims, blockSize, dataType, new GzipCompression());
		final DatasetAttributes uniqueAttributes = new DatasetAttributes(dims, blockSize, DataType.UINT64, new GzipCompression());
		container.createGroup(dataset);
		final String dataGroup = String.join("/", dataset, "data");
		final String uniqueLabelsGroup = String.join("/", dataset, "unique-labels");
		container.createGroup(dataGroup);
		container.createGroup(uniqueLabelsGroup);
		final String s0 = String.join("/", dataGroup, "s0");
		final String u0 = String.join("/", uniqueLabelsGroup, "s0");

		container.createDataset(s0, attributes);
		container.createDataset(u0, uniqueAttributes);
		additionalAttributes.forEach(ThrowingBiConsumer.unchecked((k, v) -> container.setAttribute(dataGroup, k, v)));
		additionalAttributes.forEach(ThrowingBiConsumer.unchecked((k, v) -> container.setAttribute(s0, k, v)));
		container.setAttribute(dataset, "painteraData", PAINTERA_DATA_ATTRIBUTE);

		container.setAttribute(dataGroup, N5Helpers.MULTI_SCALE_KEY, true);
		container.setAttribute(uniqueLabelsGroup, N5Helpers.MULTI_SCALE_KEY, true);

		Grids.forEachOffset(
				new long[dims.length],
				canvas.getCellGrid().getGridDimensions(),
				ones(dims.length),
				ThrowingConsumer.unchecked(b -> container.writeBlock(u0, uniqueAttributes, new LongArrayDataBlock(new int[] {1}, b, new long[] {}))));

		for (int scale = 0, scaleIndex = 1; scale < scaleFactors.length; ++scale) {
			final int[] sf = scaleFactors[scale];
			final long[] scaleDims = divideBy(dims, sf, 1);
			final DatasetAttributes scaleAttributes = new DatasetAttributes(scaleDims, blockSize, dataType, new GzipCompression());
			final DatasetAttributes uniqueScaleAttributes = new DatasetAttributes(scaleDims, blockSize, DataType.UINT64, new GzipCompression());
			final CellGrid scaleGrid = new CellGrid(scaleDims, blockSize);
			final String sN = String.join("/", dataGroup, "s" + scaleIndex);
			final String uN = String.join("/", uniqueLabelsGroup, "s" + scaleIndex);
			LOG.debug("Creating scale data set with scale factor {}: {}", sf, sN);
			container.createDataset(sN, scaleAttributes);
			container.createDataset(uN, uniqueScaleAttributes);
			additionalAttributes.forEach(ThrowingBiConsumer.unchecked((k, v) -> container.setAttribute(sN, k, v)));
			container.setAttribute(sN, N5Helpers.DOWNSAMPLING_FACTORS_KEY, IntStream.of(sf).asDoubleStream().toArray());
		}

		testCanvasPersistance(container, dataset, s0, canvas, openLabels, asserts);

		// test highest level block lookups
		final String uniqueBlock0 = String.join("/", dataset, "unique-labels", "s0");
		final Path mappingPattern = Paths.get(new N5FSMeta(container, null).basePath(), dataset, "label-to-block-mapping", "s%d", "%d");
		final Path mapping0 = Paths.get(new N5FSMeta(container, null).basePath(), dataset, "label-to-block-mapping", "s0");//String.join("/", dataset, "label-to-block-mapping", "s0");
		final DatasetAttributes uniqueBlockAttributes = container.getDatasetAttributes(uniqueBlock0);
		final List<Interval> blocks = Grids.collectAllContainedIntervals(dims, blockSize);
		final TLongObjectMap<TLongSet> labelToBLockMapping = new TLongObjectHashMap<>();
		for (final Interval block : blocks) {
			final TLongSet labels = new TLongHashSet();
			final long[] blockMin = Intervals.minAsLongArray(block);
			final long[] blockPos = blockMin.clone();
			Arrays.setAll(blockPos, d -> blockPos[d] / blockSize[d]);
			final long blockIndex = IntervalIndexer.positionToIndex(blockPos, canvas.getCellGrid().getGridDimensions());
			Views.interval(canvas, block).forEach(px -> {
				// blocks are loaded with default value 0 if not present, thus 0 will be in the updated data
				final long pxVal = isInvalid(px) ? 0 : px.getIntegerLong();
				labels.add(pxVal);
				if (pxVal != 0) {
					if (!labelToBLockMapping.containsKey(pxVal))
						labelToBLockMapping.put(pxVal, new TLongHashSet());
					labelToBLockMapping.get(pxVal).add(blockIndex);
				}
			});
			DataBlock<?> uniqueBlock = container.readBlock(uniqueBlock0, uniqueBlockAttributes, blockPos);
			Assert.assertEquals(labels, new TLongHashSet((long[])uniqueBlock.getData()));
		}

		long[] idsForMapping = Stream.of(mapping0.toFile().list((f, fn) -> Optional.ofNullable(f).map(File::isDirectory).orElse(false))).mapToLong(Long::parseLong).toArray();
		LOG.debug("Found ids for mapping: {}", idsForMapping);
		Assert.assertEquals(labelToBLockMapping.keySet(), new TLongHashSet(idsForMapping));
		final LabelBlockLookupFromFile lookup = new LabelBlockLookupFromFile(mappingPattern.toString());

		for (final long id : idsForMapping) {
			final Interval[] lookupFor = lookup.read(0, id);
			LOG.trace("Found mapping {} for id {}", lookupFor, id);
			Assert.assertEquals(labelToBLockMapping.get(id).size(), lookupFor.length);
			final long[] blockIndices = Stream
					.of(lookupFor)
					.map(Intervals::minAsLongArray)
					.mapToLong(m -> toBlockIndex(m, canvas.getCellGrid()))
					.toArray();
			LOG.trace("Block indices for id {}: {}", id, blockIndices);
			Assert.assertEquals(labelToBLockMapping.get(id), new TLongHashSet(blockIndices));
		}


	}

	private static <T> void testMultiScale(
			final N5Writer container,
			final String dataset,
			final DataType dataType,
			final CachedCellImg<UnsignedLongType, ?> canvas,
			final BiFunction<N5Reader, String, RandomAccessibleInterval<T>> openLabels,
			final BiConsumer<UnsignedLongType, T> asserts
	) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup {
		testMultiScale(container, dataset, dataType, canvas, openLabels, asserts, new HashMap<>());
	}

	private static <T> void testMultiScale(
			final N5Writer container,
			final String dataset,
			final DataType dataType,
			final CachedCellImg<UnsignedLongType, ?> canvas,
			final BiFunction<N5Reader, String, RandomAccessibleInterval<T>> openLabels,
			final BiConsumer<UnsignedLongType, T> asserts,
			final Map<String, Object> additionalAttributes
	) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup {
		final DatasetAttributes attributes = new DatasetAttributes(canvas.getCellGrid().getImgDimensions(), blockSize(canvas.getCellGrid()), dataType, new GzipCompression());
		container.createGroup(dataset);
		final String s0 = String.join("/", dataset, "s0");
		container.createDataset(s0, attributes);
		additionalAttributes.forEach(ThrowingBiConsumer.unchecked((k, v) -> container.setAttribute(dataset, k, v)));
		additionalAttributes.forEach(ThrowingBiConsumer.unchecked((k, v) -> container.setAttribute(s0, k, v)));
		container.setAttribute(s0, N5Helpers.MULTI_SCALE_KEY, true);
		testCanvasPersistance(container, dataset, s0, canvas, openLabels, asserts);
	}

	private static <T> void testSingleScale(
			final N5Writer container,
			final String dataset,
			final DataType dataType,
			final CachedCellImg<UnsignedLongType, ?> canvas,
			final BiFunction<N5Reader, String, RandomAccessibleInterval<T>> openLabels,
			final BiConsumer<UnsignedLongType, T> asserts
	) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup {
		testSingleScale(container, dataset, dataType, canvas, openLabels, asserts, new HashMap<>());
	}

	private static <T> void testSingleScale(
			final N5Writer container,
			final String dataset,
			final DataType dataType,
			final CachedCellImg<UnsignedLongType, ?> canvas,
			final BiFunction<N5Reader, String, RandomAccessibleInterval<T>> openLabels,
			final BiConsumer<UnsignedLongType, T> asserts,
			final Map<String, Object> additionalAttributes
	) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup {
		final DatasetAttributes attributes = new DatasetAttributes(canvas.getCellGrid().getImgDimensions(), blockSize(canvas.getCellGrid()), dataType, new GzipCompression());
		container.createDataset(dataset, attributes);
		additionalAttributes.forEach(ThrowingBiConsumer.unchecked((k, v) -> container.setAttribute(dataset, k, v)));
		testCanvasPersistance(container, dataset, canvas, openLabels, asserts);
	}

	private static <T> void testCanvasPersistance(
			final N5Writer container,
			final String group,
			final CachedCellImg<UnsignedLongType, ?> canvas,
			final BiFunction<N5Reader, String, RandomAccessibleInterval<T>> openLabels,
			final BiConsumer<UnsignedLongType, T> asserts) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup {

		testCanvasPersistance(container, group, group, canvas, openLabels, asserts);
	}

	private static <T> void testCanvasPersistance(
			final N5Writer container,
			final String group,
			final String labelsDataset,
			final CachedCellImg<UnsignedLongType, ?> canvas,
			final BiFunction<N5Reader, String, RandomAccessibleInterval<T>> openLabels,
			final BiConsumer<UnsignedLongType, T> asserts) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup {

		writeAll(container, group, canvas);

		final RandomAccessibleInterval<T> labels = openLabels.apply(container, labelsDataset);
		Assert.assertArrayEquals(Intervals.dimensionsAsLongArray(canvas), Intervals.dimensionsAsLongArray(labels));

		for (final Pair<UnsignedLongType, T> pair : Views.interval(Views.pair(canvas, labels), labels)) {
			LOG.trace("Comparing canvas {} and background {}", pair.getA(), pair.getB());
			asserts.accept(pair.getA(), pair.getB());
		}
	}


	private static void writeAll(
			final N5Writer container,
			final String dataset,
			final CachedCellImg<UnsignedLongType, ?> canvas) throws IOException, UnableToPersistCanvas, UnableToUpdateLabelBlockLookup {
		final long numBlocks = Intervals.numElements(canvas.getCellGrid().getGridDimensions());
		final long[] blocks = new long[(int) numBlocks];
		Arrays.setAll(blocks, d -> d);

		final CommitCanvasN5 cc = new CommitCanvasN5(container, dataset);
		final List<TLongObjectMap<PersistCanvas.BlockDiff>> blockDiffs = cc.persistCanvas(canvas, blocks);
		if (cc.supportsLabelBlockLookupUpdate())
			cc.updateLabelBlockLookup(blockDiffs);
	}

	private static int[] blockSize(final CellGrid grid) {
		int[] blockSize = new int[grid.numDimensions()];
		grid.cellDimensions(blockSize);
		return blockSize;
	}

	private static long[] divideBy(final long[] divident, final int[] divisor, final long minValue) {
		long[] quotient = new long[divident.length];
		Arrays.setAll(quotient, d -> Math.max(divident[d] / divisor[d] + (divident[d] % divisor[d] == 0 ? 0 : 1), minValue));
		return quotient;
	}

	private static int[] ones(final int n) {
		final int[] ones = new int[n];
		Arrays.fill(ones, 1);
		return ones;
	}

	private static long toBlockIndex(final long[] intervalMin, final CellGrid grid) {
		grid.getCellPosition(intervalMin, intervalMin);
		return IntervalIndexer.positionToIndex(intervalMin, grid.getGridDimensions());
	}

}
