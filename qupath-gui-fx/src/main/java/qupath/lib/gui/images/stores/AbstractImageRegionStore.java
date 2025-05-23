/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2025 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.lib.gui.images.stores;

import java.awt.Shape;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.Weigher;

import qupath.lib.common.ThreadTools;
import qupath.lib.images.servers.GeneratingImageServer;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;


/**
 * A generic ImageRegionStore.
 *
 * @author Pete Bankhead
 * @param <T> the generic parameter for an image (most likely BufferedImage)
 */
abstract class AbstractImageRegionStore<T> implements ImageRegionStore<T> {

	private static final Logger logger = LoggerFactory.getLogger(AbstractImageRegionStore.class);

	private static final int DEFAULT_THUMBNAIL_WIDTH = 1024; // Increased from 1000 before v0.6.0

	// Workers who can get individual tiles
	private final List<TileWorker<T>> workers = Collections.synchronizedList(new ArrayList<>());
	
	// Set of regions for which a tile has been requested, but not yet fully loaded
	private final Map<RegionRequest, TileWorker<T>> waitingMap = new ConcurrentHashMap<>();

	private boolean clearingCache = false; // Flag that cache is currently being cleared
	
	protected List<TileListener<T>> tileListeners = Collections.synchronizedList(new ArrayList<>());

	// Cache of image tiles for specified regions
	protected Map<RegionRequest, T> cache;

	/**
	 * Maximum size of thumbnail, in any dimension.
	 */
	private int maxThumbnailSize;
	
	/**
	 * Minimum size of thumbnail, in any dimension.
	 */
	private int minThumbnailSize = 16;

	/**
	 * Maximum tile cache size, in bytes
	 */
	private long tileCacheSizeBytes;
	
	private final TileRequestManager manager = new TileRequestManager(10);
	
	// Create two threadpools: a larger one for images that need to be fetched (e.g. from disk, cloud storage), and a smaller one
	// for painting image tiles... the reason being that the high latency of distantly-stored images otherwise risks lowering
	// repainting performance
	private final ExecutorService pool = Executors.newFixedThreadPool(Math.max(8, Math.min(Runtime.getRuntime().availableProcessors() * 4, 32)), ThreadTools.createThreadFactory("region-store-", false));
	private final ExecutorService poolLocal = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), ThreadTools.createThreadFactory("region-store-local-", false));
	
	

	protected AbstractImageRegionStore(final SizeEstimator<T> sizeEstimator, final int thumbnailSize, final long tileCacheSizeBytes) {
		this.maxThumbnailSize = thumbnailSize;
		this.tileCacheSizeBytes = tileCacheSizeBytes;
		
		// Because Guava uses integer weights, and we sometimes have *very* large images, we convert our size estimates KB
		Weigher<RegionRequest, T> weigher = (var r, var t) -> (int)Long.min(Integer.MAX_VALUE, sizeEstimator.getApproxImageSize(t)/1024);
		long maxWeight = Long.max(1, tileCacheSizeBytes / 1024);
		// If concurrency > 1, the maximum size for an individual tile becomes maxWeight/concurrencyLevel
		// This makes it more difficult to tune the cache size when working with large, non-pyramidal images
		int concurrencyLevel = 1;
		Cache<RegionRequest, T> originalCache = CacheBuilder.newBuilder()
				.weigher(weigher)
				.maximumWeight(maxWeight)
				.softValues()
				.concurrencyLevel(concurrencyLevel)
//				.recordStats()
				.removalListener(n -> {
					if (n.getCause() == RemovalCause.COLLECTED) {
                        logger.debug("Cached tile collected: {} (cache size={})", n.getKey(), cache.size());
					} else {
						logger.trace("Cached tile removed due to {}: {} (cache size={})", n.getCause(), n.getKey(), cache.size());
					}}).build();
		cache = originalCache.asMap();
	}

	
	protected AbstractImageRegionStore(final SizeEstimator<T> sizeEstimator, final long tileCacheSizeBytes) {
		this(sizeEstimator, DEFAULT_THUMBNAIL_WIDTH, tileCacheSizeBytes);
	}

	/**
	 * Get the tile cache size, in bytes.
	 * Image tiles larger than this cannot be cached.
	 * @return
	 */
	public long getTileCacheSize() {
		return tileCacheSizeBytes;
	}
	
	/**
	 * Calculate the downsample value to use when generating a thumbnail image.
	 * @param width
	 * @param height
	 * @return
	 */
	double calculateThumbnailDownsample(int width, int height) {
		// We'll have trouble if we try to downsample until we have very few pixels in any dimension
		double maxDim = Math.max(width, height);
		double minDim = Math.min(width, height);
		if (minDim > minThumbnailSize) {
			double maxDownsample = minDim / minThumbnailSize;
			return Math.max(1, Math.min(maxDim / maxThumbnailSize, maxDownsample));
		}
		return 1.0;
	}
	

	RegionRequest getThumbnailRequest(final ImageServer<T> server, final int zPosition, final int tPosition) {
		// Determine thumbnail size
		double downsample = 1;
		if (isPyramidalImageServer(server)) {
			downsample = calculateThumbnailDownsample(server.getWidth(), server.getHeight());
		}
		// Ensure we aren't accidentally upsampling (shouldn't actually happen)
		downsample = Math.max(downsample, 1);
		return RegionRequest.createInstance(server.getPath(), downsample, 0, 0, server.getWidth(), server.getHeight(), zPosition, tPosition);
	}
	
	
	/* (non-Javadoc)
	 * @see qupath.lib.images.stores.ImageRegionStore#getCachedThumbnail(qupath.lib.images.servers.ImageServer, int, int)
	 */
	@Override
	public T getCachedThumbnail(ImageServer<T> server, int zPosition, int tPosition) {
		RegionRequest request = getThumbnailRequest(server, zPosition, tPosition);
		return cache.get(request);
	}


	/**
	 * Register a request so that the tile request queue will be populated, including potentially for nearby tiles (i.e. preemptively).
	 * @param tileListener
	 * @param server
	 * @param clipShape
	 * @param downsampleFactor
	 * @param zPosition
	 * @param tPosition
	 */
	protected void registerRequest(final TileListener<T> tileListener, final ImageServer<T> server, final Shape clipShape, final double downsampleFactor, final int zPosition, final int tPosition) {
		manager.registerRequest(tileListener, server, clipShape, downsampleFactor, zPosition, tPosition);
	}
	
	
	/* (non-Javadoc)
	 * @see qupath.lib.images.stores.ImageRegionStore#addTileListener(qupath.lib.images.stores.TileListener)
	 */
	@Override
	public void addTileListener(TileListener<T> listener) {
		tileListeners.add(listener);
	}

	
	
	public Map<RegionRequest, T> getCache() {
		return cache;
	}
	
	
	/* (non-Javadoc)
	 * @see qupath.lib.images.stores.ImageRegionStore#removeTileListener(qupath.lib.images.stores.TileListener)
	 */
	@Override
	public void removeTileListener(TileListener<T> listener) {
		tileListeners.remove(listener);
	}
	

	
	/* (non-Javadoc)
	 * @see qupath.lib.images.stores.ImageRegionStore#getCachedTile(qupath.lib.images.servers.ImageServer, qupath.lib.regions.RegionRequest)
	 */
	@Override
	public T getCachedTile(ImageServer<T> server, RegionRequest request) {
		return cache.get(request);
	}	
	
	/**
	 * Get a map of all cached tiles pertaining to a specific ImageServer.
	 * @param server
	 * @return
	 */
	public synchronized Map<RegionRequest, T> getCachedTilesForServer(ImageServer<T> server) {
		Map<RegionRequest, T> tiles = new HashMap<>();
		var serverPath = server.getPath();
		for (var entry : cache.entrySet()) {
			if (entry.getValue() != null && entry.getKey().getPath().equals(serverPath))
				tiles.put(entry.getKey(), entry.getValue());
		}
		return tiles;
	}	
	
	
	private static boolean isPyramidalImageServer(ImageServer<?> server) {
		return server.nResolutions() > 1;
	}
	
	
	/**
	 * Every TileWorker should call this when the task is done!
	 * 
	 * @param worker
	 */
	protected void workerComplete(final TileWorker<T> worker) {
		workers.remove(worker);
		manager.taskCompleted(worker);
   		if (worker.isCancelled() || !stopWaiting(worker.getRequest())) {
   			return;
   		}
		try {
			T imgNew = worker.get();
	   		if (imgNew == null)
	   			return;
	   		RegionRequest request = worker.getRequest();
	   		worker.getRequestedCache().put(request, imgNew);
	   		// Notify listeners that we have a new tile, if desired
	    	List<TileListener<T>> myTileListeners = new ArrayList<>(tileListeners);
	   		for (TileListener<T> listener : myTileListeners)
	   			listener.tileAvailable(request.getPath(), request, imgNew);
		} catch (InterruptedException e) {
			logger.warn("Tile request interrupted", e);
		} catch (ExecutionException e) {
			logger.warn("Tile request exception", e);
		}
	}
	
	
	protected T getCachedRegion(final ImageServer<T> server, final RegionRequest request) {
		if (server == null)
			return null;
		// Only need to use server path & region as the hash key, because we are relying on the tile size never changing...
		// so different requests should never end up wanting the same region
		// If this gives trouble, the downsample could be added
		Object result = requestImageTile(server, request, cache, false);
		if (!(result == null || result instanceof TileWorker<?>)) {
			@SuppressWarnings("unchecked")
			T img = (T)result;
			return img;
		}
		return null;
	}
	
	
	protected boolean stopWaiting(final RegionRequest request) {
		if (clearingCache) {
			//			synchronized(this) {
			logger.warn("Stop waiting called while clearing cache: {}", Thread.currentThread());
			return waitingMap.remove(request) != null;
			//			}
		} else
			return waitingMap.remove(request) != null;
	}
	
	
	/**
	 * Request an image tile.
	 * There are 3 possible return values:
	 * 	- a T for the tile
	 * 	- the {@code TileWorker<T>} object currently charged with fetching the tile
	 * 	- null, if this is the value stored in the TiledImageCache (i.e. the tile has previously been fetched, and there is no image corresponding to the request)
	 * @param server
	 * @param request
	 * @param cache 
	 * @param ensureTileReturned 
	 * @return
	 */
	protected synchronized Object requestImageTile(final ImageServer<T> server, final RegionRequest request, final Map<RegionRequest, T> cache, final boolean ensureTileReturned) {
		T img = cache.get(request);
		if (img != null)
			return img;
		// If the cache contains the key, but simply returns null because nothing should be painted, also return null here
		if (cache.containsKey(request))
			return null;
		// If the region request can be known to return null quickly, avoid making a full request
		// (at the time of writing, this only makes a difference with PathHierarchyImageServers)
		if (server.isEmptyRegion(request)) {
//			cache.put(request, null); // Guava cache does not support null
			return null;
		}
		// Start a worker & add to the list
		TileWorker<T> worker = null;
		worker = (TileWorker<T>)waitingMap.get(request); // TODO: Consider if this is a bad idea...
		if (worker != null && worker.isCancelled()) {
			// Try to fix a bug with z-projection overlays where the projection was lost when the cache filled up
			workers.remove(worker);
			worker = null;
		}
		if (worker == null) {
			worker = createTileWorker(server, request, cache, ensureTileReturned);
			workers.add(worker);
			if (server instanceof GeneratingImageServer) {
				if (poolLocal.isShutdown())
					return null;
				poolLocal.execute(worker);
			}
			else {
				if (pool.isShutdown())
					return null;
				pool.execute(worker);
			}
			waitingMap.put(request, worker);
		}
		return worker;
	}
	
	
	
//	protected abstract TileWorker<T> createTileWorker(final BaseImageServer<T> server, final RegionRequest request, final RegionCache<T> cache, final boolean ensureTileReturned);

	protected TileWorker<T> createTileWorker(final ImageServer<T> server, final RegionRequest request, final Map<RegionRequest, T> cache, final boolean ensureTileReturned) {
		return new DefaultTileWorker(server, request, cache, ensureTileReturned);
	}

	
	
	/* (non-Javadoc)
	 * @see qupath.lib.images.stores.ImageRegionStore#getThumbnail(qupath.lib.images.servers.ImageServer, int, int, boolean)
	 */
	@Override
	@SuppressWarnings("unchecked")
	public synchronized T getThumbnail(ImageServer<T> server, int zPosition, int tPosition, boolean addToCache) {
		RegionRequest request = getThumbnailRequest(server, zPosition, tPosition);
		Object result = requestImageTile(server, request, cache, true);
		if (!(result instanceof TileWorker<?>))
			return (T)result;
		
		logger.debug("Thumbnail request for {}, ({}, {})", server, zPosition, tPosition);
		TileWorker<T> worker = (TileWorker<T>)result;
		try {
			return worker.get();
		} catch (InterruptedException e) {
			logger.error(e.getLocalizedMessage());
		} catch (ExecutionException e) {
			logger.error(e.getLocalizedMessage());
		}
		try {
			// Last resort... shouldn't happen
			logger.warn("Fallback to requesting thumbnail directly...");
			return server.readRegion(request);
		} catch (IOException e) {
            logger.error("Unable to obtain thumbnail for {}", request, e);
			return null;
		}
	}
	
	
	/**
	 * Clear the cache, including thumbnails, and cancel any pending requests.
	 */
	public synchronized void clearCache() {
		clearCache(true);
	}
	
	
	/**
	 * Clear the cache, optionally including thumbnails and stopping any pending requests.
	 * 
	 * @param stopWaiting cancel any tasks that are currently fetching tiles
	 */
	public synchronized void clearCache(final boolean stopWaiting) {
		clearingCache = true;
		// Try to cancel anything we're waiting for
		try {
			if (stopWaiting) {
				for (TileWorker<?> worker : waitingMap.values().toArray(TileWorker[]::new)) {
					worker.cancel(true);
				}
				waitingMap.clear();
				workers.clear();
			}
			cache.clear();
		} finally {
			clearingCache = false;
		}
	}
	
	
	/* (non-Javadoc)
	 * @see qupath.lib.images.stores.ImageRegionStore#clearCacheForServer(qupath.lib.images.servers.ImageServer)
	 */
	@Override
	public synchronized void clearCacheForServer(final ImageServer<T> server) {
		clearingCache = true;
		// Ensure any current requests are discarded
		try {
			if (!waitingMap.isEmpty()) {
				String serverPath = server.getPath();
				logger.trace("Waiting map size before server cache cleared: {}", waitingMap.size());
				Iterator<Entry<RegionRequest, TileWorker<T>>> iter = waitingMap.entrySet().iterator();
				while (iter.hasNext()) {
					logger.trace("Waiting map size during server clear: {}", waitingMap.size());
					Entry<RegionRequest, TileWorker<T>> entry = iter.next();
					if (serverPath.equals(entry.getKey().getPath())) {
						logger.trace("Removing entry from waiting map for thread  {}", Thread.currentThread().threadId());
						iter.remove();
						entry.getValue().cancel(true);
						workers.remove(entry.getValue());
					}
				}
			}
			clearCacheForServer(cache, server);
		} finally {
			clearingCache = false;			
		}
	}
	
	/* (non-Javadoc)
	 * @see qupath.lib.images.stores.ImageRegionStore#clearCacheForRequestOverlap(qupath.lib.regions.RegionRequest)
	 */
	@Override
	public synchronized void clearCacheForRequestOverlap(final RegionRequest request) {
		// Ensure any current requests are discarded
		if (!waitingMap.isEmpty()) {
			Iterator<Entry<RegionRequest, TileWorker<T>>> iter = waitingMap.entrySet().iterator();
			while (iter.hasNext()) {
				Entry<RegionRequest, TileWorker<T>> entry = iter.next();
				if (request.overlapsRequest(entry.getKey())) {
					iter.remove();
					entry.getValue().cancel(true);
					workers.remove(entry.getValue());
				}
			}
		}
		clearCacheForRequestOverlap(cache, request);
	}
	
	
	
	private synchronized void clearCacheForServer(Map<RegionRequest, T> map, ImageServer<?> server) {
		String serverPath = server.getPath();
        map.entrySet().removeIf(entry -> serverPath.equals(entry.getKey().getPath()));
	}
	
	private synchronized void clearCacheForRequestOverlap(Map<RegionRequest, T> map, RegionRequest request) {
        map.entrySet().removeIf(regionRequestTEntry -> request.overlapsRequest(regionRequestTEntry.getKey()));
	}
	
	
	/* (non-Javadoc)
	 * @see qupath.lib.images.stores.ImageRegionStore#close()
	 */
	@Override
	public void close() {
		// Try to cancel all workers
		for (TileWorker<T> worker : new ArrayList<>(workers))
			worker.cancel(true);
		pool.shutdownNow();
		poolLocal.shutdownNow();
		cache.clear();
	}
	
	
	
	
	
	class TileRequestManager {
		
		static final int MAX_Z_SEPARATION = 10;
		private List<TileRequestCollection<T>> list = new ArrayList<>();
		
		private final TileRequestComparator<T> comparator = new TileRequestComparator<>();
		private final int nThreads;
		private int busyThreads = 0;
		
		private final List<TileWorker<T>> requestedWorkers = new ArrayList<>();
				
		TileRequestManager(final int nThreads) {
			this.nThreads = nThreads;
		}
		
		
		public synchronized void registerRequest(final TileListener<T> tileListener, final ImageServer<T> server, final Shape clipShape, final double downsampleFactor, final int zPosition, final int tPosition) {
			
			// Check if the listener has already put in a request - if so, check if it is the same & discard it if not
			Iterator<TileRequestCollection<T>> iter = list.iterator();
			while (iter.hasNext()) {
				TileRequestCollection<T> temp = iter.next();
				if (temp.tileListener == tileListener) {
					if (temp.clipShape.equals(clipShape) && temp.zPosition == zPosition && temp.tPosition == tPosition)
						return;
					iter.remove();
					break;
				}
			}
			
			// Create a new request
			TileRequestCollection<T> requestCollection = new TileRequestCollection<>(tileListener, server, clipShape, downsampleFactor, zPosition, tPosition, 10);
			list.add(requestCollection);
			Collections.sort(list, comparator);
			assignTasks();
			
		}
		
		public synchronized void deregisterRequest(final TileListener<T> tileListener) {
            list.removeIf(temp -> temp.tileListener == tileListener);
		}
		
		
		synchronized void assignTasks() {
			if (list.isEmpty())
				return;
			int ind = 0;
			while (busyThreads < nThreads && ind < list.size()) {
				TileRequestCollection<T> temp = list.get(ind);
				if (!temp.hasMoreTiles()) {
					list.remove(temp);
					continue;
				}
				RegionRequest request = temp.nextTileRequest();
				if (cache.containsKey(request) || waitingMap.containsKey(request))
					continue;
				
				TileWorker<T> worker = createTileWorker(temp.server, request, cache, false);
				logger.trace("Adding {} to waiting map for thread {}", request, Thread.currentThread().getId());
				waitingMap.put(request, worker);
				if (temp.server instanceof GeneratingImageServer) {
					if (!poolLocal.isShutdown())
						poolLocal.execute(worker);
				} else {
					if (!pool.isShutdown())
						pool.execute(worker);
				}
				requestedWorkers.add(worker);
				busyThreads++;
			}
			Collections.sort(list, comparator);
		}
		
		
		synchronized void taskCompleted(final TileWorker<T> worker) {
			if (!requestedWorkers.remove(worker))
				return;
			busyThreads--;
            logger.trace("Number of busy threads: {}", busyThreads);
			Collections.sort(list, comparator);
			assignTasks();
		}
		
		
	}
	
	
	
	static class TileRequestCollection<T> {
		
		private long timestamp;
		private List<RegionRequest> tileRequests = new ArrayList<>();
		private int zSeparation = 0;
		private int maxZSeparation = 0;
		
		private TileListener<T> tileListener;
		private ImageServer<T> server;
		private Shape clipShape;
		private double downsampleFactor;
		private int zPosition;
		private int tPosition;
				
		
		TileRequestCollection(final TileListener<T> tileListener, final ImageServer<T> server, final Shape clipShape, final double downsampleFactor, final int zPosition, final int tPosition, final int maxZSeparation) {
			timestamp = System.currentTimeMillis();
			this.tileListener = tileListener;
			this.server = server;
			this.clipShape = clipShape;
			this.downsampleFactor = downsampleFactor;
			this.zPosition = zPosition;
			this.tPosition = tPosition;
			this.zSeparation = 0;
			this.maxZSeparation = server == null ? 0 : Math.min(server.nZSlices()-1, maxZSeparation); // Used for requests that go along z dimension
			updateRequests();
		}
		
		void updateRequests() {
			if (zSeparation == 0)
				updateRequestsForZ(zPosition, downsampleFactor, false);
			else {
				if (zPosition - zSeparation >= 0)
					updateRequestsForZ(zPosition - zSeparation, downsampleFactor * Math.max(5, zSeparation*2), true);
				if (zPosition + zSeparation < server.nZSlices())
					updateRequestsForZ(zPosition + zSeparation, downsampleFactor * Math.max(5, zSeparation*2), true);
			}
		}
		
		void updateRequestsForZ(final int z, final double downsample, final boolean stopBeforeDownsample) {
			// Add tile requests in ascending order of resolutions, to support (faster) progressive image display
			if (server == null)
				return;
			boolean firstLoop = true;
			double[] downsamples = server.getPreferredDownsamples();
			Arrays.sort(downsamples);
			for (int i = downsamples.length-1; i >= 0; i--) {
				double d = downsamples[i];
				if (Double.isNaN(d))
					continue;
				if (firstLoop || !stopBeforeDownsample || d > downsampleFactor) {
					int sizeBefore = tileRequests.size();
					tileRequests = ImageRegionStoreHelpers.getTilesToRequest(server, clipShape, downsample, z, tPosition, tileRequests);
					int sizeAfter = tileRequests.size();
					logger.trace("Requests added: {} - z separation = {}, downsample = {}", (sizeAfter - sizeBefore), zSeparation, downsample);
				}
				firstLoop = false;
				if (d <= downsampleFactor)
					return;
			}
			
		}
		
		
		public boolean hasMoreTiles() {
			return !tileRequests.isEmpty();
		}
		
		public RegionRequest nextTileRequest() {
			int ind = tileRequests.size() - 1;
			assert ind >= 0; // TODO: Throw RunTimeException?
			RegionRequest request = tileRequests.remove(ind);
			if (ind == 0 && zSeparation < maxZSeparation) {
				zSeparation++;
				updateRequests();
			}
			return request;
		}
		
	}
	
	
	static class TileRequestComparator<T> implements Comparator<TileRequestCollection<T>> {

		
		@Override
		public int compare(TileRequestCollection<T> r1, TileRequestCollection<T> r2) {
			int zDiff = r1.zSeparation - r2.zSeparation;
			if (zDiff == 0)
				return (int)(r1.timestamp - r2.timestamp);
			else
				return zDiff;
		}
		
	}
	
	
	
	
	
	
	
	/**
	 * Worker for fetching image tiles asynchronously & adding to the tile cache.
	 */
	class DefaultTileWorker extends FutureTask<T> implements TileWorker<T> {
		
		private final Map<RegionRequest, T> cache;
		private final RegionRequest request;
		
		DefaultTileWorker(final ImageServer<T> server, final RegionRequest request, final Map<RegionRequest, T> cache, final boolean ensureTileReturned) {
			super(new Callable<>() {

				@Override
				public T call() throws Exception {
					// Check if the cache now contains the region
					// (e.g. it came from a different viewer... probably shouldn't occur now)
					T imgTile = cache.get(request);
					if (imgTile != null)
						return imgTile;
					// TODO: Investigate the (current) purpose of ensureTileReturned... doesn't seem to do anything here
					if (ensureTileReturned)
						return server.readRegion(request);
					// Check if we still need the tile... if not, and we go searching, there can be a backlog
					// making any requests slower to fulfill
					// (Also, grab a snapshot of the listener list to avoid concurrent modifications)
                    return server.readRegion(request);
				}

			});
			this.request = request;
			this.cache = cache;
		}
		
	    
	    @Override
		public RegionRequest getRequest() {
	    	return request;
	    }
	    
	    @Override
		public Map<RegionRequest, T> getRequestedCache() {
	    	return cache;
	    }

	    
	    @Override
		public void done() {
	    	workerComplete(this);
	    }
	    
	}
	
}