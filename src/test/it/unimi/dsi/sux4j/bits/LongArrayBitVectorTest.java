package test.it.unimi.dsi.sux4j.bits;

import it.unimi.dsi.sux4j.bits.LongArrayBitVector;
import junit.framework.TestCase;

public class LongArrayBitVectorTest extends TestCase {

	public void testCopy() {
		LongArrayBitVector v = LongArrayBitVector.getInstance();
		int[] bits = { 0,0,0,0,1,1,1,0,0,0,0,1,1,0,0 };
		for( int i = 0; i < bits.length; i++ ) v.add( bits[ i ] );
		LongArrayBitVector c = LongArrayBitVector.getInstance();
		for( int i = 5; i < bits.length; i++ ) c.add( bits[ i ] );
		assertEquals( v.copy( 5, 15 ), c );
	
	}

}
