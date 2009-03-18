package it.unimi.dsi.sux4j.mph;

/*		 
 * Sux4J: Succinct data structures for Java
 *
 * Copyright (C) 2008 Sebastiano Vigna 
 *
 *  This library is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU Lesser General Public License as published by the Free
 *  Software Foundation; either version 2.1 of the License, or (at your option)
 *  any later version.
 *
 *  This library is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 *  for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 *
 */

import it.unimi.dsi.Util;
import it.unimi.dsi.bits.BitVector;
import it.unimi.dsi.bits.LongArrayBitVector;
import it.unimi.dsi.bits.TransformationStrategy;
import it.unimi.dsi.fastutil.io.FastByteArrayOutputStream;
import it.unimi.dsi.fastutil.objects.AbstractObject2LongFunction;
import it.unimi.dsi.io.InputBitStream;
import it.unimi.dsi.io.NullOutputStream;
import it.unimi.dsi.io.OutputBitStream;
import it.unimi.dsi.lang.MutableString;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

import org.apache.log4j.Logger;

/** A succinct implementation of a binary partial compacted trie based on a recursive bitstream.
 * 
 * <p>Instances of this class represent a <em>partial compacted trie</em> (PaCo trie). In such a trie,
 * just a prefix of the path at each node is actually stored: then, we just store the number of missing bits.
 * 
 * <p>The main purpose of PaCo tries is to serve as <em>distributors</em> for other data structures:
 * given a set of delimiters <var>D</var> of a set <var>S</var>, a PaCo trie will {@linkplain #getLong(Object) <em>rank</em>}
 * an elements <var>x</var> of <var>S</var> over <var>D</var>, that is, it will return how many elements of
 * <var>D</var> strictly precede <var>x</var>. To do so, a PaCo trie records at each node the smallest possible
 * prefix that make it possible to rank correctly the whole of <var>S</var>: depending on the strings in
 * <var>S</var>, the savings in space can be more or less significant.
 * 
 * <p>An instance of this class stores a trie as a <em>recursive bitstream</em>: a node <var>x</var> with
 * subtrees <var>A</var> and <var>B</var> is stored as
 *  <div style="text-align: center">
 *  <var>skip</var> <var>pathlen</var> <var>path</var> <var>missing</var> <var>leaves<sub>A</sub></var> <var>A</var> <var>B</var>,
 *  </div>
 * where except for <var>path</var>, which is the path at <var>x</var> represented literally,
 * all other components are numbers in {@linkplain OutputBitStream#writeDelta(int) &delta; coding}, and the
 * last two components are the recursive encodings of <var>A</var> and <var>B</var>. Leaves are
 * distinguished by having <var>skip</var> equal to zero (in which case, no information after the path is recorded). 
 * <var>leaves<sub>A</sub></var> is the number of leaves of <var>A</var>.
 * 
 * @author Sebastiano Vigna
 */

public class VLPaCoTrieDistributor<T> extends AbstractObject2LongFunction<T> {
	private final static Logger LOGGER = Util.getLogger( VLPaCoTrieDistributor.class );
	private static final long serialVersionUID = 1L;
	private static final boolean DEBUG = false;
	private static final boolean DDEBUG = false;
	/** Infinity-like value for initialising node prefixes. It's one less than {@link Integer#MAX_VALUE} because we need to be able to add one
	 * without overflowing. */
	private static final int MAX_PREFIX = Integer.MAX_VALUE - 1;
	/** The bitstream representing the PaCo trie. */
	private final byte[] trie;
	/** The number of leaves in the trie. */
	private final int numberOfLeaves;
	/** The transformation used to map object to bit vectors. */
	private final TransformationStrategy<? super T> transformationStrategy;
	public long[] offset;
	
	/** A class representing explicitly a partial trie. The {@link PartialTrie#toStream(OutputBitStream)} method
	 * writes an instance of this class to a bit stream. 
	 */
	private final static class PartialTrie<T> {
		private final static boolean ASSERTS = true;

		/** A node in the trie. */
		protected static class Node {
			/** Left child. */
			public Node left;
			/** Right child. */
			public Node right;
			/** The path compacted in this node (<code>null</code> if there is no compaction at this node). */
			public final LongArrayBitVector path;
			/** The length of the minimum disambiguating prefix on the left. */
			public int prefixLeft;
			/** The length of the minimum disambiguating prefix on the right. */
			public int prefixRight;
			
			/** Creates a node. 
			 * 
			 * @param left the left child.
			 * @param right the right child.
			 * @param path the path compacted at this node.
			 */
			public Node( final Node left, final Node right, final LongArrayBitVector path ) {
				this.left = left;
				this.right = right;
				this.path = path;
				prefixLeft = prefixRight = MAX_PREFIX;
			}

			/** Returns true if this node is a leaf.
			 * 
			 * @return true if this node is a leaf.
			 */
			public boolean isLeaf() {
				return right == null && left == null;
			}
			
			public String toString() {
				return "[" + path + "]";
			}
			
		}
			
		/** The root of the trie. */
		protected final Node root;
		
		/** The offset of each delimiter. */
		protected final long offset[];
		
		/** Creates a partial compacted trie using given elements, bucket size and transformation strategy.
		 * 
		 * @param elements the elements among which the trie must be able to rank.
		 * @param bucketSize the size of a bucket.
		 * @param transformationStrategy a transformation strategy that must turn the elements in <code>elements</code> into a list of
		 * distinct, lexicographically increasing (in iteration order) bit vectors.
		 */
		
		public PartialTrie( final Iterable<? extends T> elements, final int numElements, final int bucketSize, final TransformationStrategy<? super T> transformationStrategy ) {
			Iterator<? extends T> iterator = elements.iterator(); 
			
			Node node;
			BitVector curr;
			int pos, prefix;

			if ( iterator.hasNext() ) {
				LongArrayBitVector prev = LongArrayBitVector.copy( transformationStrategy.toBitVector( iterator.next() ) );
				LongArrayBitVector shortest = prev.copy();
				// The last delimiter seen, if root is not null.
				LongArrayBitVector prevDelimiter = LongArrayBitVector.getInstance();
				
				int count = 1;
				Node root = null;
				int cmp;
				long maxLength = prev.length();
				// Last element will be unused
				offset = new long[ numElements / bucketSize + 1 ];
				
				while( iterator.hasNext() ) {
					// Check order
					curr = transformationStrategy.toBitVector( iterator.next() ).fast();
					cmp = prev.compareTo( curr );
					if ( cmp == 0 ) throw new IllegalArgumentException( "The input bit vectors are not distinct" );
					if ( cmp > 0 ) throw new IllegalArgumentException( "The input bit vectors are not lexicographically sorted" );
					if ( curr.longestCommonPrefixLength( prev ) == prev.length() ) throw new IllegalArgumentException( "The input bit vectors are not prefix-free" );

					if ( count % bucketSize == 0 ) {
						// Found delimiter. Insert into trie.
						if ( root == null ) {
							root = new Node( null, null, shortest.copy() );
							prevDelimiter.replace( shortest );
						}
						else {
							prefix = (int)shortest.longestCommonPrefixLength( prevDelimiter );

							pos = 0;
							node = root;
							Node n = null;
							while( node != null ) {
								final long pathLength = node.path.length();
								if ( prefix < pathLength ) {
									n = new Node( node.left, node.right, node.path.copy( prefix + 1, pathLength ) );
									node.path.length( prefix );
									node.path.trim();
									node.left = n;
									node.right = new Node( null, null, shortest.copy( pos + prefix + 1, shortest.length() ) ); 
									break;
								}

								prefix -= pathLength + 1;
								pos += pathLength + 1;
								node = node.right;
								if ( ASSERTS ) assert node == null || prefix >= 0 : prefix + " <= " + 0;
							}

							if ( ASSERTS ) assert node != null;

							prevDelimiter.replace( shortest );
						}
						
						shortest.replace( curr );
						offset[ count / bucketSize ] = count;
					}

					if ( curr.length() < shortest.length() ) {
						shortest.replace( curr );
						offset[ count / bucketSize ] = count;
					}

					prev.replace( curr );
					maxLength = Math.max( maxLength, prev.length() );
					count++;
				}
				
				if ( DDEBUG ) System.err.println( "Offsets: " + Arrays.toString( offset ) );			

				this.root = root;

				if ( root != null ) {
					if ( false && ASSERTS ) {
						iterator = elements.iterator();
						int c = 1;
						while( iterator.hasNext() ) {
							curr = transformationStrategy.toBitVector( iterator.next() );
							if ( c++ % bucketSize == 0 ) {
								if ( ! iterator.hasNext() ) break; // The last string is never a delimiter
								node = root;
								pos = 0;
								while( node != null ) {
									prefix = (int)curr.subVector( pos ).longestCommonPrefixLength( node.path );
									assert prefix == node.path.length() : "Error at delimiter " + ( c - 1 ) / bucketSize;
									pos += node.path.length() + 1;
									if ( pos <= curr.length() ) node = curr.getBoolean( pos - 1 ) ? node.right : node.left;
									else {
										assert node.left == null && node.right == null;
										break;
									}
								}
							}
						}
					}

					LOGGER.info( "Reducing paths..." );

					iterator = elements.iterator();

					// The stack of nodes visited the last time
					final Node stack[] = new Node[ (int)maxLength ];
					// The length of the path compacted in the trie up to the corresponding node, excluded
					final int[] len = new int[ (int)maxLength ];
					stack[ 0 ] = root;
					int depth = 0;
					boolean first = true;

					while( iterator.hasNext() ) {
						curr = transformationStrategy.toBitVector( iterator.next() ).fast();
						if ( ! first )  {
							// Adjust stack using lcp between present string and previous one
							prefix = (int)prev.longestCommonPrefixLength( curr );
							while( depth > 0 && len[ depth ] > prefix ) depth--;
						}
						else first = false;
						node = stack[ depth ];
						pos = len[ depth ];
						for(;;) {
							final LongArrayBitVector path = node.path;
							prefix = (int)curr.subVector( pos ).longestCommonPrefixLength( path );
							if ( prefix < path.length() ) {
								/* If we are at the left of the current node, we simply update prefixLeft. Otherwise,
								 * can update prefixRight only *once*. */
								if ( path.getBoolean( prefix ) ) node.prefixLeft = prefix;
								else if ( node.prefixRight == MAX_PREFIX ) node.prefixRight = prefix; 
								break;
							}

							pos += path.length() + 1;
							if ( pos > curr.length() ) break;
							node = curr.getBoolean( pos - 1 ) ? node.right : node.left;
							// Update stack
							len[ ++depth ] = pos;
							stack[ depth ] = node;
						}

						prev.replace( curr );
					}
				}
			}
			else {
				this.root = null;
				offset = null;
			}

		}

		/** Accumulates the gain in bits w.r.t. a standard trie (just for statistical purposes). */
		protected int gain;

		private final OutputBitStream bitCount = new OutputBitStream( NullOutputStream.getInstance(), 0 );
		
		/** Writes this trie in bit stream format to the given stream.
		 * 
		 * @param obs an output bit stream.
		 * @return the number of leaves in the trie.
		 */
		public int toStream( final OutputBitStream obs ) throws IOException {
			final int result = toStream( root, obs );
			LOGGER.info( "Gain: " + gain );
			return result;
		}
		
		private int toStream( final Node n, final OutputBitStream obs ) throws IOException {
			if ( n == null ) return 0;
			
			if ( ASSERTS ) assert ( n.left != null ) == ( n.right != null );
			
			// We recursively create the stream of the left and right trees
			final FastByteArrayOutputStream leftStream = new FastByteArrayOutputStream();
			final OutputBitStream left = new OutputBitStream( leftStream, 0 );
			int leavesLeft = toStream( n.left, left );
			long leftBits = left.writtenBits();
			left.flush();
			
			final FastByteArrayOutputStream rightStream = new FastByteArrayOutputStream();
			final OutputBitStream right = new OutputBitStream( rightStream, 0 );
			int leavesRight = toStream( n.right, right );
			long rightBits = right.writtenBits();
			right.flush();
			
			obs.writeLongDelta( n.isLeaf() ? 0 : leftBits ); // Skip pointer (nonzero if non leaf)
			
			final int pathLength = (int)Math.min( n.path.length(), Math.max( n.prefixLeft, n.prefixRight ) + 1 );

			final int missing =  (int)( n.path.length() - pathLength );
			// We gain one bit for each missing bit
			gain += missing;
			
			/* For efficiency, the path is written in 64-bit blocks exactly as 
			 * it is represented in a LongArrayBitVector. */
			
			gain += bitCount.writeLongDelta( n.path.length() ) - obs.writeDelta( pathLength ); // We gain if the path length is written in less bits than it should be.
			if ( pathLength > 0 ) for( int i = 0; i < pathLength; i += Long.SIZE ) obs.writeLong( n.path.getLong( i, Math.min( i + Long.SIZE, pathLength ) ), Math.min( Long.SIZE, pathLength - i ) );

			// Nothing after the path in leaves.
			if ( n.isLeaf() ) return 1;
			
			// We count the missing bit as a gain, but of course in an internal node we must subtract the space needed to represent their cardinality.
			gain -= obs.writeDelta( missing );

			obs.writeLongDelta( leavesLeft ); // The number of leaves in the left subtree

			// Write left and right trees
			obs.write( leftStream.array, leftBits );
			obs.write( rightStream.array, rightBits );

			return leavesLeft + leavesRight;
		}
		
		private void recToString( final Node n, final MutableString printPrefix, final MutableString result, final MutableString path, final int level ) {
			if ( n == null ) return;
			
			result.append( printPrefix ).append( '(' ).append( level ).append( ')' );
			
			if ( n.path != null ) {
				path.append( n.path );
				result.append( " path:" ).append( n.path );
			}

			result.append( '\n' );
			
			path.append( '0' );
			recToString( n.left, printPrefix.append( '\t' ).append( "0 => " ), result, path, level + 1 );
			path.charAt( path.length() - 1, '1' ); 
			recToString( n.right, printPrefix.replace( printPrefix.length() - 5, printPrefix.length(), "1 => "), result, path, level + 1 );
			path.delete( path.length() - 1, path.length() ); 
			printPrefix.delete( printPrefix.length() - 6, printPrefix.length() );
			
			//System.err.println( "Path now: " + path + " Going to delete from " + ( path.length() - n.pathLength));
			
			path.delete( (int)( path.length() - n.path.length() ), path.length() );
		}
		
		public String toString() {
			MutableString s = new MutableString();
			recToString( root, new MutableString(), s, new MutableString(), 0 );
			return s.toString();
		}

	}
	
	/** Creates a partial compacted trie using given elements, bucket size and transformation strategy.
	 * 
	 * @param elements the elements among which the trie must be able to rank.
	 * @param bucketSize the size of a bucket.
	 * @param transformationStrategy a transformation strategy that must turn the elements in <code>elements</code> into a list of
	 * distinct, lexicographically increasing (in iteration order) bit vectors.
	 */
	public VLPaCoTrieDistributor( final Iterable<? extends T> elements, final int numElements, final int bucketSize, final TransformationStrategy<? super T> transformationStrategy ) throws IOException {
		this.transformationStrategy = transformationStrategy;
		PartialTrie<T> immutableBinaryTrie = new PartialTrie<T>( elements, numElements, bucketSize, transformationStrategy );
		FastByteArrayOutputStream fbStream = new FastByteArrayOutputStream();
		OutputBitStream trie = new OutputBitStream( fbStream, 0 );
		numberOfLeaves = immutableBinaryTrie.toStream( trie );
		offset = immutableBinaryTrie.offset;

		LOGGER.info(  "trie bit size:" + trie.writtenBits() );
		
		trie.flush();
		fbStream.trim();
		this.trie = fbStream.array;

		if ( DDEBUG ) {
			MutableString s = new MutableString();
			recToString( new InputBitStream( this.trie ), new MutableString(), s, new MutableString(), 0 );
			System.err.println( s );
		}
	}
	
	
	@SuppressWarnings("unchecked")
	public long getLong( Object o ) {
		if ( numberOfLeaves == 0 ) return 0;
		try {
			if ( DEBUG ) System.err.println( "Getting " + o + "...");
			final BitVector v = transformationStrategy.toBitVector( (T)o ).fast();
			final long length = v.length();
			final InputBitStream trie = new InputBitStream( this.trie );

			long pos = 0, readBits, skip, xor, t;
			int leavesOnTheLeft = 0, pathLength, size, missing, leftSubtrieLeaves, leaves = numberOfLeaves;
			for( ;; ) {
				skip = trie.readLongDelta();
				pathLength = trie.readDelta();
				if ( DEBUG ) System.err.println( "Path length: " + pathLength );
				
				xor = t = 0;
				
				readBits = trie.readBits();
				
				for( int i = 0; i < ( pathLength + Long.SIZE - 1 ) / Long.SIZE; i++ ) {
					size = Math.min( Long.SIZE, pathLength - i * Long.SIZE );
					xor = v.getLong( pos, Math.min( length, pos += size ) ) ^ ( t = trie.readLong( size ) );
					if ( xor != 0 || pos >= length ) break;
				}

				if ( xor != 0 || pos > length ) {
					if ( DEBUG ) System.err.println( "Path mismatch: " +  ( ( ( ( xor & -xor ) & t ) != 0 ) ? "smaller" : "greater" ) + " than trie path at (leaf = " + leavesOnTheLeft + ")" );
					// If we are lexicographically smaller than the trie, we just return the leaves to our left.
					if ( ( ( xor & -xor ) & t ) != 0 ) return leavesOnTheLeft;
					else {
						if ( skip == 0 ) {
							if ( DEBUG ) System.err.println( "Leaf node" );
							return leavesOnTheLeft + 1;
						}
						if ( DEBUG ) System.err.println( "Non-leaf node" );
						// Skip remaining path, if any and missing bits count
						trie.skip( pathLength - ( trie.readBits() - readBits ) );
						trie.readDelta();
						return leavesOnTheLeft + leaves;
					}
				}

				if ( skip == 0 ) {
					if ( DEBUG ) System.err.println( "Exact match (leaf = " + leavesOnTheLeft + ")" + pos + " " + length );
					return leavesOnTheLeft;
				}
				
				missing = trie.readDelta();
				if ( DEBUG ) System.err.println( "Missing bits: " + missing );
				
				// Increment pos by missing bits
				pos += missing;
				if ( pos >= v.length() ) return leavesOnTheLeft;
				
				leftSubtrieLeaves = trie.readDelta();
				
				if ( v.getBoolean( pos++ ) ) {
					// Right
					trie.skip( skip );
					leavesOnTheLeft += leftSubtrieLeaves;
					leaves -= leftSubtrieLeaves;
					if ( DEBUG ) System.err.println( "Turining right (" + leavesOnTheLeft + " leaves on the left)..." );
				}
				else {
					// Left
					leaves = leftSubtrieLeaves;
					if ( DEBUG ) System.err.println( "Turining left (" + leavesOnTheLeft + " leaves on the left)..." );
				}

			} 
		} catch( IOException cantHappen ) {
			throw new RuntimeException( cantHappen );
		}
	}
	

	private void recToString( final InputBitStream trie, final MutableString printPrefix, final MutableString result, final MutableString path, final int level ) throws IOException {
		int skip = trie.readDelta();
		
		//System.err.println( "Called with prefix " + printPrefix );
		
		result.append( printPrefix ).append( '(' ).append( level ).append( ')' );
		
		int pathLength = trie.readDelta();
		LongArrayBitVector p = LongArrayBitVector.getInstance( pathLength );
		
		for( int i = 0; i < ( pathLength + Long.SIZE - 1 ) / Long.SIZE; i++ ) {
			int size = Math.min( Long.SIZE, pathLength - i * Long.SIZE );
			p.append( trie.readLong( size ), size );
		}

		if ( skip == 0 ) return; // Leaf

		int missing = trie.readDelta();

		path.append( p );
		result.append( " path:" ).append( p );
		while( missing-- != 0 ) result.append( '*' );
		
		result.append( '\n' );

		trie.readDelta(); // Skip number of leaves in the left subtree
		
		path.append( '0' );
		recToString( trie, printPrefix.append( '\t' ).append( "0 => " ), result, path, level + 1 );
		path.charAt( path.length() - 1, '1' ); 
		recToString( trie, printPrefix.replace( printPrefix.length() - 5, printPrefix.length(), "1 => "), result, path, level + 1 );
		path.delete( path.length() - 1, path.length() ); 
		printPrefix.delete( printPrefix.length() - 6, printPrefix.length() );
		
		//System.err.println( "Path now: " + path + " Going to delete from " + ( path.length() - n.pathLength));
		
		path.delete( path.length() - pathLength, path.length() );
	}

	public long numBits() {
		return trie.length * Byte.SIZE + transformationStrategy.numBits();
	}

	/** Returns the number of leaves in this trie.
	 * 
	 * @return the number of leaves in this trie.
	 */
	public int numberOfLeaves() {
		return numberOfLeaves;
	}
	
	public boolean containsKey( Object o ) {
		return true;
	}

	public int size() {
		return -1;
	}
}