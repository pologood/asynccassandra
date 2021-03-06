/*
 * Copyright (C) 2010-2012  The Async HBase Authors.  All rights reserved.
 * This file is part of Async HBase.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the StumbleUpon nor the names of its contributors
 *     may be used to endorse or promote products derived from this software
 *     without specific prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.hbase.async;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.connectionpool.OperationResult;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.Row;
import com.netflix.astyanax.model.Rows;
import com.stumbleupon.async.Deferred;

import static org.hbase.async.HBaseClient.EMPTY_ARRAY;

/**
 * Creates a scanner to read data sequentially from HBase.
 * <p>
 * This class is <strong>not synchronized</strong> as it's expected to be
 * used from a single thread at a time.  It's rarely (if ever?) useful to
 * scan concurrently from a shared scanner using multiple threads.  If you
 * want to optimize large table scans using extra parallelism, create a few
 * scanners and give each of them a partition of the table to scan.  Or use
 * MapReduce.
 * <p>
 * Unlike HBase's traditional client, there's no method in this class to
 * explicitly open the scanner.  It will open itself automatically when you
 * start scanning by calling {@link #nextRows()}.  Also, the scanner will
 * automatically call {@link #close} when it reaches the end key.  If, however,
 * you would like to stop scanning <i>before reaching the end key</i>, you
 * <b>must</b> call {@link #close} before disposing of the scanner.  Note that
 * it's always safe to call {@link #close} on a scanner.
 * <p>
 * If you keep your scanner open and idle for too long, the RegionServer will
 * close the scanner automatically for you after a timeout configured on the
 * server side.  When this happens, you'll get an
 * {@link UnknownScannerException} when you attempt to use the scanner again.
 * Also, if you scan too slowly (e.g. you take a long time between each call
 * to {@link #nextRows()}), you may prevent HBase from splitting the region if
 * the region is also actively being written to while you scan.  For heavy
 * processing you should consider using MapReduce.
 * <p>
 * A {@code Scanner} is not re-usable.  Should you want to scan the same rows
 * or the same table again, you must create a new one.
 *
 * <h1>A note on passing {@code byte} arrays in argument</h1>
 * None of the method that receive a {@code byte[]} in argument will copy it.
 * For more info, please refer to the documentation of {@link HBaseRpc}.
 * <h1>A note on passing {@code String}s in argument</h1>
 * All strings are assumed to use the platform's default charset.
 */
public final class Scanner implements Runnable {

  private static final Logger LOG = LoggerFactory.getLogger(Scanner.class);

  /**
   * The default maximum number of {@link KeyValue}s the server is allowed
   * to return in a single RPC response to a {@link Scanner}.
   * <p>
   * This default value is exposed only as a hint but the value itself
   * is not part of the API and is subject to change without notice.
   * @see #setMaxNumKeyValues
   */
  public static final int DEFAULT_MAX_NUM_KVS = 4096;

  /**
   * The default maximum number of rows to scan per RPC.
   * <p>
   * This default value is exposed only as a hint but the value itself
   * is not part of the API and is subject to change without notice.
   * @see #setMaxNumRows
   */
  public static final int DEFAULT_MAX_NUM_ROWS = 512;
  
  private final HBaseClient client;
  private final byte[] table;
  private final ExecutorService executor; // do the dew
  private final Keyspace keyspace;
  
  private OperationResult<Rows<byte[], byte[]>> results;
  private Iterator<Row<byte[], byte[]>> iterator;
  
  /**
   * The key to start scanning from.  An empty array means "start from the
   * first key in the table".  This key is updated each time we move on to
   * another row, so that in the event of a failure, we know what was the
   * last key previously returned.  Note that this doesn't entail that the
   * full row was returned.  Depending on the failure, we may not know if
   * the last key returned was only a subset of a row or a full row, so it
   * may not be possible to gracefully recover from certain errors without
   * re-scanning and re-returning the same data twice.
   */
  private byte[] start_key = EMPTY_ARRAY;

  /**
   * The last key to scan up to (exclusive).
   * An empty array means "scan until the last key in the table".
   */
  private byte[] stop_key = EMPTY_ARRAY;

  private byte[][] families;
  private byte[][][] qualifiers;

  /** Filter to apply on the scanner.  */
  private ScanFilter filter;

  /** Minimum {@link KeyValue} timestamp to scan.  */
  private long min_timestamp = 0;

  /** Maximum {@link KeyValue} timestamp to scan.  */
  private long max_timestamp = Long.MAX_VALUE;

  /** @see #setServerBlockCache  */
  private boolean populate_blockcache = true;

  /**
   * Maximum number of rows to fetch at a time.
   * @see #setMaxNumRows
   */
  private int max_num_rows = DEFAULT_MAX_NUM_ROWS;

  /**
   * Maximum number of KeyValues to fetch at a time.
   * @see #setMaxNumKeyValues
   */
  private int max_num_kvs = DEFAULT_MAX_NUM_KVS;

  /**
   * Maximum number of bytes to fetch at a time.
   * Except that HBase won't truncate a row in the middle or what,
   * so we could potentially go a bit above that.
   * Only used when talking to HBase 0.95 and up.
   * @see #setMaxNumBytes
   */
  private long max_num_bytes = ~HBaseRpc.MAX_BYTE_ARRAY_MASK;

  /**
   * How many versions of each cell to retrieve.
   */
  private int versions = 1;

  /**
   * This is the scanner ID we got from the RegionServer.
   * It's generated randomly so any {@code long} value is possible.
   */
  private long scanner_id;
  
  private Deferred<ArrayList<ArrayList<KeyValue>>> deferred;

  /**
   * Constructor.
   * <strong>This byte array will NOT be copied.</strong>
   * @param table The non-empty name of the table to use.
   */
  Scanner(final HBaseClient client, final ExecutorService executor, final byte[] table, final Keyspace keyspace) {
    this.client = client;
    KeyValue.checkTable(table);
    this.executor = executor;
    this.table = table;
    this.keyspace = keyspace;
  }

  /**
   * Returns the row key this scanner is currently at.
   * <strong>Do not modify the byte array returned.</strong>
   * @return the row key this scanner is currently at.
   */
  public byte[] getCurrentKey() {
    return start_key;
  }

  /**
   * Specifies from which row key to start scanning (inclusive).
   * @param start_key The row key to start scanning from.  If you don't invoke
   * this method, scanning will begin from the first row key in the table.
   * <strong>This byte array will NOT be copied.</strong>
   * @throws IllegalStateException if scanning already started.
   */
  public void setStartKey(final byte[] start_key) {
    KeyValue.checkKey(start_key);
    checkScanningNotStarted();
    this.start_key = start_key;
  }

  /**
   * Specifies from which row key to start scanning (inclusive).
   * @param start_key The row key to start scanning from.  If you don't invoke
   * this method, scanning will begin from the first row key in the table.
   * @see #setStartKey(byte[])
   * @throws IllegalStateException if scanning already started.
   */
  public void setStartKey(final String start_key) {
    setStartKey(start_key.getBytes());
  }

  /**
   * Specifies up to which row key to scan (exclusive).
   * @param stop_key The row key to scan up to.  If you don't invoke
   * this method, or if the array is empty ({@code stop_key.length == 0}),
   * every row up to and including the last one will be scanned.
   * <strong>This byte array will NOT be copied.</strong>
   * @throws IllegalStateException if scanning already started.
   */
  public void setStopKey(final byte[] stop_key) {
    KeyValue.checkKey(stop_key);
    checkScanningNotStarted();
    this.stop_key = stop_key;
  }

  /**
   * Specifies up to which row key to scan (exclusive).
   * @param stop_key The row key to scan up to.  If you don't invoke
   * this method, or if the array is empty ({@code stop_key.length == 0}),
   * every row up to and including the last one will be scanned.
   * @see #setStopKey(byte[])
   * @throws IllegalStateException if scanning already started.
   */
  public void setStopKey(final String stop_key) {
    setStopKey(stop_key.getBytes());
  }

  /**
   * Specifies a particular column family to scan.
   * @param family The column family.
   * <strong>This byte array will NOT be copied.</strong>
   * @throws IllegalStateException if scanning already started.
   */
  public void setFamily(final byte[] family) {
    KeyValue.checkFamily(family);
    checkScanningNotStarted();
    families = new byte[][] { family };
  }

  /**
   * Specifies a particular column family to scan.
   * @param family The column family.
   */
  public void setFamily(final String family) {
    setFamily(family.getBytes());
  }

  /**
   * Specifies multiple column families to scan.
   * <p>
   * If {@code qualifiers} is not {@code null}, then {@code qualifiers[i]}
   * is assumed to be the list of qualifiers to scan in the family
   * {@code families[i]}.  If {@code qualifiers[i]} is {@code null}, then
   * all the columns in the family {@code families[i]} will be scanned.
   * @param families Array of column families names.
   * @param qualifiers Array of column qualifiers.  Can be {@code null}.
   * <strong>This array of byte arrays will NOT be copied.</strong>
   * @throws IllegalStateException if scanning already started.
   * @since 1.5
   */
  public void setFamilies(byte[][] families, byte[][][] qualifiers) {
    checkScanningNotStarted();
    for (int i = 0; i < families.length; i++) {
      KeyValue.checkFamily(families[i]);
      if (qualifiers != null && qualifiers[i] != null) {
        for (byte[] qualifier : qualifiers[i]) {
          KeyValue.checkQualifier(qualifier);
        }
      }
    }
    this.families = families;
    this.qualifiers = qualifiers;
  }

  /**
   * Specifies multiple column families to scan.
   * @param families The column families to scan.
   * @since 1.5
   */
  public void setFamilies(final String... families) {
    checkScanningNotStarted();
    this.families = new byte[families.length][];
    for (int i = 0; i < families.length; i++) {
      this.families[i] = families[i].getBytes();
      KeyValue.checkFamily(this.families[i]);
      qualifiers[i] = null;
    }
  }

  /**
   * Specifies a particular column qualifier to scan.
   * <p>
   * Note that specifying a qualifier without a family has no effect.
   * You need to call {@link #setFamily(byte[])} too.
   * @param qualifier The column qualifier.
   * <strong>This byte array will NOT be copied.</strong>
   * @throws IllegalStateException if scanning already started.
   */
  public void setQualifier(final byte[] qualifier) {
    KeyValue.checkQualifier(qualifier);
    checkScanningNotStarted();
    this.qualifiers = new byte[][][] { { qualifier } };
  }

  /**
   * Specifies a particular column qualifier to scan.
   * @param qualifier The column qualifier.
   */
  public void setQualifier(final String qualifier) {
    setQualifier(qualifier.getBytes());
  }

  /**
   * Specifies one or more column qualifiers to scan.
   * <p>
   * Note that specifying qualifiers without a family has no effect.
   * You need to call {@link #setFamily(byte[])} too.
   * @param qualifiers The column qualifiers.
   * <strong>These byte arrays will NOT be copied.</strong>
   * @throws IllegalStateException if scanning already started.
   * @since 1.4
   */
  public void setQualifiers(final byte[][] qualifiers) {
    checkScanningNotStarted();
    for (final byte[] qualifier : qualifiers) {
      KeyValue.checkQualifier(qualifier);
    }
    this.qualifiers = new byte[][][] { qualifiers };
  }

  /**
   * Specifies the filter to apply to this scanner.
   * @param filter The filter.  If {@code null}, then no filter will be used.
   * @since 1.5
   */
  public void setFilter(final ScanFilter filter) {
    this.filter = filter;
  }

  /**
   * Returns the possibly-{@code null} filter applied to this scanner.
   * @return the possibly-{@code null} filter applied to this scanner.
   * @since 1.5
   */
  public ScanFilter getFilter() {
    return filter;
  }

  /**
   * Clears any filter that was previously set on this scanner.
   * <p>
   * This is a shortcut for {@link #setFilter}{@code (null)}
   * @since 1.5
   */
  public void clearFilter() {
    filter = null;
  }

  /**
   * Sets a regular expression to filter results based on the row key.
   * <p>
   * This is equivalent to calling
   * {@link #setFilter setFilter}{@code (new }{@link
   * KeyRegexpFilter}{@code (regexp))}
   * @param regexp The regular expression with which to filter the row keys.
   */
  public void setKeyRegexp(final String regexp) {
    filter = new KeyRegexpFilter(regexp);
  }

  /**
   * Sets a regular expression to filter results based on the row key.
   * <p>
   * This is equivalent to calling
   * {@link #setFilter setFilter}{@code (new }{@link
   * KeyRegexpFilter}{@code (regexp, charset))}
   * @param regexp The regular expression with which to filter the row keys.
   * @param charset The charset used to decode the bytes of the row key into a
   * string.  The RegionServer must support this charset, otherwise it will
   * unexpectedly close the connection the first time you attempt to use this
   * scanner.
   */
  public void setKeyRegexp(final String regexp, final Charset charset) {
    filter = new KeyRegexpFilter(regexp, charset);
  }

  /**
   * Sets whether or not the server should populate its block cache.
   * @param populate_blockcache if {@code false}, the block cache of the server
   * will not be populated as the rows are being scanned.  If {@code true} (the
   * default), the blocks loaded by the server in order to feed the scanner
   * <em>may</em> be added to the block cache, which will make subsequent read
   * accesses to the same rows and other neighbouring rows faster.  Whether or
   * not blocks will be added to the cache depend on the table's configuration.
   * <p>
   * If you scan a sequence of keys that is unlikely to be accessed again in
   * the near future, you can help the server improve its cache efficiency by
   * setting this to {@code false}.
   * @throws IllegalStateException if scanning already started.
   */
  public void setServerBlockCache(final boolean populate_blockcache) {
    checkScanningNotStarted();
    this.populate_blockcache = populate_blockcache;
  }

  /**
   * Sets the maximum number of rows to scan per RPC (for better performance).
   * <p>
   * Every time {@link #nextRows()} is invoked, up to this number of rows may
   * be returned.  The default value is {@link #DEFAULT_MAX_NUM_ROWS}.
   * <p>
   * <b>This knob has a high performance impact.</b>  If it's too low, you'll
   * do too many network round-trips, if it's too high, you'll spend too much
   * time and memory handling large amounts of data.  The right value depends
   * on the size of the rows you're retrieving.
   * <p>
   * If you know you're going to be scanning lots of small rows (few cells, and
   * each cell doesn't store a lot of data), you can get better performance by
   * scanning more rows by RPC.  You probably always want to retrieve at least
   * a few dozen kilobytes per call.
   * <p>
   * If you want to err on the safe side, it's better to use a value that's a
   * bit too high rather than a bit too low.  Avoid extreme values (such as 1
   * or 1024) unless you know what you're doing.
   * <p>
   * Note that unlike many other methods, it's fine to change this value while
   * scanning.  Changing it will take affect all the subsequent RPCs issued.
   * This can be useful you want to dynamically adjust how much data you want
   * to receive at once (provided that you can estimate the size of your rows).
   * @param max_num_rows A strictly positive integer.
   * @throws IllegalArgumentException if the argument is zero or negative.
   */
  public void setMaxNumRows(final int max_num_rows) {
    if (max_num_rows <= 0) {
      throw new IllegalArgumentException("zero or negative argument: "
                                         + max_num_rows);
    }
    this.max_num_rows = max_num_rows;
  }

  /**
   * Sets the maximum number of {@link KeyValue}s the server is allowed to
   * return in a single RPC response.
   * <p>
   * If you're dealing with wide rows, in which you have many cells, you may
   * want to limit the number of cells ({@code KeyValue}s) that the server
   * returns in a single RPC response.
   * <p>
   * The default is {@link #DEFAULT_MAX_NUM_KVS}, unlike in HBase's client
   * where the default is {@code -1}.  If you set this to a negative value,
   * the server will always return full rows, no matter how wide they are.  If
   * you request really wide rows, this may cause increased memory consumption
   * on the server side as the server has to build a large RPC response, even
   * if it tries to avoid copying data.  On the client side, the consequences
   * on memory usage are worse due to the lack of framing in RPC responses.
   * The client will have to buffer a large RPC response and will have to do
   * several memory copies to dynamically grow the size of the buffer as more
   * and more data comes in.
   * @param max_num_kvs A non-zero value.
   * @throws IllegalArgumentException if the argument is zero.
   * @throws IllegalStateException if scanning already started.
   */
  public void setMaxNumKeyValues(final int max_num_kvs) {
    if (max_num_kvs == 0) {
      throw new IllegalArgumentException("batch size can't be zero");
    }
    checkScanningNotStarted();
    this.max_num_kvs = max_num_kvs;
  }

  /**
   * Maximum number of {@link KeyValue}s the server is allowed to return.
   * @return the maximum number of {@link KeyValue}s the server is allowed to
   * return.
   * @see #setMaxNumKeyValues
   * @since 1.5
   */
  public int getMaxNumKeyValues() {
    return max_num_kvs;
  }

  /**
   * Sets the maximum number of versions to return for each cell scanned.
   * <p>
   * By default a scanner will only return the most recent version of
   * each cell.  If you want to get all possible versions available,
   * pass {@link Integer#MAX_VALUE} in argument.
   * @param versions A strictly positive number of versions to return.
   * @since 1.4
   * @throws IllegalStateException if scanning already started.
   * @throws IllegalArgumentException if {@code versions <= 0}
   */
  public void setMaxVersions(final int versions) {
    if (versions <= 0) {
      throw new IllegalArgumentException("Need a strictly positive number: "
                                         + versions);
    }
    checkScanningNotStarted();
    this.versions = versions;
  }

  /**
   * Returns the maximum number of versions to return for each cell scanned.
   * @return A strictly positive integer.
   * @since 1.4
   */
  public int getMaxVersions() {
    return versions;
  }

  /**
   * Sets the maximum number of bytes returned at once by the scanner.
   * <p>
   * HBase may actually return more than this many bytes because it will not
   * truncate a row in the middle.
   * <p>
   * This value is only used when communicating with HBase 0.95 and newer.
   * For older versions of HBase this value is silently ignored.
   * @param max_num_bytes A strictly positive number of bytes.
   * @since 1.5
   * @throws IllegalStateException if scanning already started.
   * @throws IllegalArgumentException if {@code max_num_bytes <= 0}
   */
  public void setMaxNumBytes(final long max_num_bytes) {
    if (max_num_bytes <= 0) {
      throw new IllegalArgumentException("Need a strictly positive number of"
                                         + " bytes, got " + max_num_bytes);
    }
    checkScanningNotStarted();
    this.max_num_bytes = max_num_bytes;
  }

  /**
   * Returns the maximum number of bytes returned at once by the scanner.
   * @return the maximum number of bytes returned at once by the scanner.
   * @see #setMaxNumBytes
   * @since 1.5
   */
  public long getMaxNumBytes() {
    return max_num_bytes;
  }

  /**
   * Sets the minimum timestamp to scan (inclusive).
   * <p>
   * {@link KeyValue}s that have a timestamp strictly less than this one
   * will not be returned by the scanner.  HBase has internal optimizations to
   * avoid loading in memory data filtered out in some cases.
   * @param timestamp The minimum timestamp to scan (inclusive).
   * @throws IllegalArgumentException if {@code timestamp < 0}.
   * @throws IllegalArgumentException if {@code timestamp > getMaxTimestamp()}.
   * @see #setTimeRange
   * @since 1.3
   */
  public void setMinTimestamp(final long timestamp) {
    if (timestamp < 0) {
      throw new IllegalArgumentException("Negative timestamp: " + timestamp);
    } else if (timestamp > max_timestamp) {
      throw new IllegalArgumentException("New minimum timestamp (" + timestamp
                                         + ") is greater than the maximum"
                                         + " timestamp: " + max_timestamp);
    }
    checkScanningNotStarted();
    min_timestamp = timestamp;
  }

  /**
   * Returns the minimum timestamp to scan (inclusive).
   * @return A positive integer.
   * @since 1.3
   */
  public long getMinTimestamp() {
    return min_timestamp;
  }

  /**
   * Sets the maximum timestamp to scan (exclusive).
   * <p>
   * {@link KeyValue}s that have a timestamp greater than or equal to this one
   * will not be returned by the scanner.  HBase has internal optimizations to
   * avoid loading in memory data filtered out in some cases.
   * @param timestamp The maximum timestamp to scan (exclusive).
   * @throws IllegalArgumentException if {@code timestamp < 0}.
   * @throws IllegalArgumentException if {@code timestamp < getMinTimestamp()}.
   * @see #setTimeRange
   * @since 1.3
   */
  public void setMaxTimestamp(final long timestamp) {
    if (timestamp < 0) {
      throw new IllegalArgumentException("Negative timestamp: " + timestamp);
    } else if (timestamp < min_timestamp) {
      throw new IllegalArgumentException("New maximum timestamp (" + timestamp
                                         + ") is greater than the minimum"
                                         + " timestamp: " + min_timestamp);
    }
    checkScanningNotStarted();
    max_timestamp = timestamp;
  }

  /**
   * Returns the maximum timestamp to scan (exclusive).
   * @return A positive integer.
   * @since 1.3
   */
  public long getMaxTimestamp() {
    return max_timestamp;
  }

  /**
   * Sets the time range to scan.
   * <p>
   * {@link KeyValue}s that have a timestamp that do not fall in the range
   * {@code [min_timestamp; max_timestamp[} will not be returned by the
   * scanner.  HBase has internal optimizations to avoid loading in memory
   * data filtered out in some cases.
   * @param min_timestamp The minimum timestamp to scan (inclusive).
   * @param max_timestamp The maximum timestamp to scan (exclusive).
   * @throws IllegalArgumentException if {@code min_timestamp < 0}
   * @throws IllegalArgumentException if {@code max_timestamp < 0}
   * @throws IllegalArgumentException if {@code min_timestamp > max_timestamp}
   * @since 1.3
   */
  public void setTimeRange(final long min_timestamp, final long max_timestamp) {
    if (min_timestamp > max_timestamp) {
      throw new IllegalArgumentException("New minimum timestamp (" + min_timestamp
                                         + ") is greater than the new maximum"
                                         + " timestamp: " + max_timestamp);
    } else if (min_timestamp < 0) {
      throw new IllegalArgumentException("Negative minimum timestamp: "
                                         + min_timestamp);
    }
    checkScanningNotStarted();
    // We now have the guarantee that max_timestamp >= 0, no need to check it.
    this.min_timestamp = min_timestamp;
    this.max_timestamp = max_timestamp;
  }

  /**
   * Scans a number of rows.  Calling this method is equivalent to:
   * <pre>
   *   this.{@link #setMaxNumRows setMaxNumRows}(nrows);
   *   this.{@link #nextRows() nextRows}();
   * </pre>
   * @param nrows The maximum number of rows to retrieve.
   * @return A deferred list of rows.
   * @see #setMaxNumRows
   * @see #nextRows()
   */
  public Deferred<ArrayList<ArrayList<KeyValue>>> nextRows(final int nrows) {
    setMaxNumRows(nrows);
    return nextRows();
  }

  /**
   * Scans a number of rows.
   * <p>
   * The last row returned may be partial if it's very wide and
   * {@link #setMaxNumKeyValues} wasn't called with a negative value in
   * argument.
   * <p>
   * Once this method returns {@code null} once (which indicates that this
   * {@code Scanner} is done scanning), calling it again leads to an undefined
   * behavior.
   * @return A deferred list of rows.  Each row is a list of {@link KeyValue}
   * and each element in the list returned represents a different row.  Rows
   * are returned in sequential order.  {@code null} is returned if there are
   * no more rows to scan.  Otherwise its {@link ArrayList#size size} is
   * guaranteed to be less than or equal to the value last given to
   * {@link #setMaxNumRows}.
   * @see #setMaxNumRows
   * @see #setMaxNumKeyValues
   */
  public Deferred<ArrayList<ArrayList<KeyValue>>> nextRows() {
    client.incrementScans();
    deferred = new Deferred<ArrayList<ArrayList<KeyValue>>>();
    executor.execute(this);
    return deferred;
  }

  /**
   * Closes this scanner (don't forget to call this when you're done with it!).
   * <p>
   * Closing a scanner already closed has no effect.  The deferred returned
   * will be called back immediately.
   * @return A deferred object that indicates the completion of the request.
   * The {@link Object} has not special meaning and can be {@code null}.
   */
  public Deferred<Object> close() {
//    if (region == null || region == DONE) {
//      return Deferred.fromResult(null);
//    }
//    return client.closeScanner(this).addBoth(closedCallback());
//    if (scanner != null) {
//      scanner.close();
//    }
    return Deferred.fromResult(null);
  }

  public String toString() {
//    final String region = this.region == null ? "null"
//      : this.region == DONE ? "none" : this.region.toString();
    final String filter = this.filter == null ? "null"
      : this.filter.toString();
    int fam_length = 0;
    if (families == null) {
      fam_length = 4;
    } else {
      for (byte[] family : families) {
        fam_length += family.length + 2 + 2;
      }
    }
    int qual_length = 0;
    if (qualifiers == null) {
      qual_length = 4;
    } else {
      for (byte[][] qualifier : qualifiers) {
        if (qualifier != null) {
          for (byte[] qual : qualifier) {
            qual_length += qual.length + 2 + 1;
          }
        }
      }
    }
    final StringBuilder buf = new StringBuilder(14 + 1 + table.length + 1 + 12
      + 1 + start_key.length + 1 + 11 + 1 + stop_key.length + 1
      + 11 + 1 + fam_length + qual_length + 1
      + 23 + 5 + 15 + 5 + 14 + 6
      //+ 9 + 1 + region.length() + 1
      + 9 + 1 + filter.length() + 1
      + 13 + 18 + 1);
    buf.append("Scanner(table=");
    Bytes.pretty(buf, table);
    buf.append(", start_key=");
    Bytes.pretty(buf, start_key);
    buf.append(", stop_key=");
    Bytes.pretty(buf, stop_key);
    buf.append(", columns={");
    familiesToString(buf);
    buf.append("}, populate_blockcache=").append(populate_blockcache)
      .append(", max_num_rows=").append(max_num_rows)
      .append(", max_num_kvs=").append(max_num_kvs)
      //.append(", region=").append(region)
      .append(", filter=").append(filter);
    buf.append(", scanner_id=").append(Bytes.hex(scanner_id))
      .append(')');
    return buf.toString();
  }

  /** Helper method for {@link toString}.  */
  private void familiesToString(final StringBuilder buf) {
    if (families == null) {
      return;
    }
    for (int i = 0; i < families.length; i++) {
      Bytes.pretty(buf, families[i]);
      if (qualifiers != null && qualifiers[i] != null) {
        buf.append(':');
        Bytes.pretty(buf, qualifiers[i]);
      }
      buf.append(", ");
    }
    buf.setLength(buf.length() - 2);  // Remove the extra ", ".
  }

  // ---------------------- //
  // Package private stuff. //
  // ---------------------- //

  byte[] table() {
    return table;
  }

  byte[] startKey() {
    return start_key;
  }

  /**
   * Invalidates this scanner and makes it assume it's no longer opened.
   * When a RegionServer goes away while we're scanning it, or some other type
   * of access problem happens, this method should be called so that the
   * scanner will have to re-locate the RegionServer and re-open itself.
   */
  void invalidate() {
    
  }

  /**
   * Throws an exception if scanning already started.
   * @throws IllegalStateException if scanning already started.
   */
  private void checkScanningNotStarted() {
//    if (region != null) {
//      throw new IllegalStateException("scanning already started");
//    }
  }

  @Override
  public void run() {
    if (families == null || families.length < 1) {
      deferred.callback(new UnsupportedOperationException(
          "Can't scan cassandra without a column family: " + this));
      return;
    }
    if (iterator == null) {
      try {
        final OperationResult<Rows<byte[], byte[]>> results = 
            keyspace.prepareQuery(client.getColumnFamilySchemas().get(families[0]))
          .withCaching(populate_blockcache)
          .getRowRange(start_key, stop_key, null, null, Integer.MAX_VALUE).execute();
        iterator = results.getResult().iterator();
      } catch (ConnectionException e) {
        deferred.callback(e);
        return;
      }
    }
    
    if (!iterator.hasNext()) {
      deferred.callback(null);
      //return Deferred.fromResult(null);
      return;
    }
    
    // dunno how to size this since we don't have the low level deets
    final ArrayList<ArrayList<KeyValue>> rows =
        new ArrayList<ArrayList<KeyValue>>();
    
    int kv_count = 0;
    while (rows.size() < max_num_rows && iterator.hasNext()) {
      final Row<byte[], byte[]> result = iterator.next();
      if (filter != null) {
        // TODO - post filtering SUCKS!!!!!
        final KeyRegexpFilter regex = (KeyRegexpFilter)filter;
        if (!regex.matches(result.getKey())) {
          continue;
        }
      }
      
      final ArrayList<KeyValue> row = new ArrayList<KeyValue>(result.getColumns().size());
      // TODO - iterator on the columns too so we can satisfy max kvs
      for (final Column<byte[]> column : result.getColumns()) {
        final KeyValue kv = new KeyValue(result.getKey(), families[0], 
            column.getName(), column.getTimestamp() / 1000, // micro to ms 
            column.getByteArrayValue());
        row.add(kv);
      }
      rows.add(row);
      kv_count += row.size();
    }
    deferred.callback(rows);
  }
}
