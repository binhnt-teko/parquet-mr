package org.apache.parquet.filter2.bloomfilterlevel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.parquet.column.values.bloomfilter.BloomFilter;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.filter2.predicate.Operators;
import org.apache.parquet.filter2.predicate.UserDefinedPredicate;
import org.apache.parquet.hadoop.BloomFilterReader;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.ColumnPath;

import static org.apache.parquet.Preconditions.checkNotNull;

public class BloomFilterImpl implements FilterPredicate.Visitor<Boolean>{
  private static final Logger LOG = LoggerFactory.getLogger(BloomFilterImpl.class);
  private static final boolean BLOCK_MIGHT_MATCH = false;
  private static final boolean BLOCK_CANNOT_MATCH = true;

  private final Map<ColumnPath, ColumnChunkMetaData> columns = new HashMap<ColumnPath, ColumnChunkMetaData>();

  public static boolean canDrop(FilterPredicate pred, List<ColumnChunkMetaData> columns, BloomFilterReader bloomFilterReader) {
    checkNotNull(pred, "pred");
    checkNotNull(columns, "columns");
    return pred.accept(new BloomFilterImpl(columns, bloomFilterReader));
  }

  private BloomFilterImpl(List<ColumnChunkMetaData> columnsList, BloomFilterReader bloomFilterReader) {
    for (ColumnChunkMetaData chunk : columnsList) {
      columns.put(chunk.getPath(), chunk);
    }

    this.bloomFilterReader = bloomFilterReader;
  }

  private BloomFilterReader bloomFilterReader;

  private ColumnChunkMetaData getColumnChunk(ColumnPath columnPath) {
    return columns.get(columnPath);
  }

  @Override
  public <T extends Comparable<T>> Boolean visit(Operators.Eq<T> eq) {
    T value = eq.getValue();

    if (value == null) {
      // the bloom filter bitset contains only non-null values so isn't helpful. this
      // could check the column stats, but the StatisticsFilter is responsible
      return BLOCK_MIGHT_MATCH;
    }

    Operators.Column<T> filterColumn = eq.getColumn();
    ColumnChunkMetaData meta = getColumnChunk(filterColumn.getColumnPath());
    if (meta == null) {
      // the column isn't in this file so all values are null, but the value
      // must be non-null because of the above check.
      return BLOCK_CANNOT_MATCH;
    }

    try {
      BloomFilter bloomFilter = bloomFilterReader.readBloomFilter(meta);
      if (bloomFilter != null && !bloomFilter.findHash(bloomFilter.hash(value))) {
        return BLOCK_CANNOT_MATCH;
      }
    } catch (RuntimeException e) {
      LOG.warn(e.getMessage());
      return BLOCK_MIGHT_MATCH;
    }

    return BLOCK_MIGHT_MATCH;
  }

  @Override
  public <T extends Comparable<T>> Boolean visit(Operators.NotEq<T> notEq) {
    return BLOCK_MIGHT_MATCH;
  }

  @Override
  public <T extends Comparable<T>> Boolean visit(Operators.Lt<T> lt) {
    return BLOCK_MIGHT_MATCH;
  }

  @Override
  public <T extends Comparable<T>> Boolean visit(Operators.LtEq<T> ltEq) {
    return BLOCK_MIGHT_MATCH;
  }

  @Override
  public <T extends Comparable<T>> Boolean visit(Operators.Gt<T> gt) {
    return BLOCK_MIGHT_MATCH;
  }

  @Override
  public <T extends Comparable<T>> Boolean visit(Operators.GtEq<T> gtEq) {
    return BLOCK_MIGHT_MATCH;
  }

  @Override
  public Boolean visit(Operators.And and) {
    return and.getLeft().accept(this) || and.getRight().accept(this);
  }

  @Override
  public Boolean visit(Operators.Or or) {
    return or.getLeft().accept(this) && or.getRight().accept(this);
  }

  @Override
  public Boolean visit(Operators.Not not) {
    throw new IllegalArgumentException(
      "This predicate contains a not! Did you forget to run this predicate through LogicalInverseRewriter? " + not);
  }

  private <T extends Comparable<T>, U extends UserDefinedPredicate<T>> Boolean visit(Operators.UserDefined<T, U> ud, boolean inverted) {
    return BLOCK_MIGHT_MATCH;
  }

  @Override
  public <T extends Comparable<T>, U extends UserDefinedPredicate<T>> Boolean visit(Operators.UserDefined<T, U> udp) {
    return visit(udp, false);
  }

  @Override
  public <T extends Comparable<T>, U extends UserDefinedPredicate<T>> Boolean visit(Operators.LogicalNotUserDefined<T, U> udp) {
    return visit(udp.getUserDefined(), true);
  }
}