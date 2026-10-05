package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST")
class MediaRangeParserTest {
    @Test void fullAndEverySingleRangeFormAreNormalizedWithLongArithmetic() {
        assertThat(MediaRangeParser.parse(null, 100)).isEqualTo(new MediaRangeParser.Selection(false, 0, 99));
        String[] headers = {"bytes=0-0","bytes=0-9","bytes=40-59","bytes=99-99","bytes=90-","bytes=-1",
                "bytes=-10","bytes=-500","bytes=90-1000","bytes=0-99"};
        long[][] positions = {{0,0},{0,9},{40,59},{99,99},{90,99},{99,99},{90,99},{0,99},{90,99},{0,99}};
        for (int i=0; i<headers.length; i++) {
            var result = MediaRangeParser.parse(headers[i],100);
            assertThat(result).isEqualTo(new MediaRangeParser.Selection(true,positions[i][0],positions[i][1]));
            assertThat(result.length()).isEqualTo(positions[i][1]-positions[i][0]+1);
        }
    }

    @ParameterizedTest @ValueSource(strings={""," ","items=0-5","Bytes=0-1","bytes=0-1,4-5","bytes=","bytes=-",
            "bytes=-1-2","bytes=abc-def","bytes=+1-2","bytes=0-+2","bytes=1.0-2","bytes=0--1","bytes=0-1-2",
            "bytes==0-1","bytes =0-1","bytes= 0-1","bytes=0 -1","bytes=0-1 ","bytes=0-1\r\n",
            "bytes=0-1\u0000","bytes=０-１","bytes=0-1;","bytes=9223372036854775808-","bytes=0-9223372036854775808"})
    void malformedAndUnsupportedHeadersAreNotUnsatisfiableRanges(String header) {
        assertThatThrownBy(() -> MediaRangeParser.parse(header,100)).isInstanceOfSatisfying(ApiFailureException.class,
                failure -> assertThat(failure.kind()).isEqualTo(ApiFailureException.Kind.MALFORMED_REQUEST));
    }

    @ParameterizedTest @ValueSource(strings={"bytes=100-","bytes=101-","bytes=100-1000","bytes=-0","bytes=9-8"})
    void syntacticallyValidButUnsatisfiableIs416(String header) {
        assertThatThrownBy(() -> MediaRangeParser.parse(header,100)).isInstanceOfSatisfying(ApiFailureException.class,
                failure -> assertThat(failure.kind()).isEqualTo(ApiFailureException.Kind.RANGE_NOT_SATISFIABLE));
    }

    @Test void deterministicBoundaryMatrixIncludesMaximumLongWithoutNarrowingOrOverflow() {
        for (long size : new long[]{1,2,31,1024,Integer.MAX_VALUE,((long)Integer.MAX_VALUE)+1,Long.MAX_VALUE}) {
            assertThat(MediaRangeParser.parse(null,size).length()).isEqualTo(size);
            assertThat(MediaRangeParser.parse("bytes=0-"+Long.MAX_VALUE,size).length()).isEqualTo(size);
            assertThat(MediaRangeParser.parse("bytes=-"+Long.MAX_VALUE,size).length()).isEqualTo(size);
            assertThat(MediaRangeParser.parse("bytes="+(size-1)+"-",size).length()).isEqualTo(1);
            assertThat(MediaRangeParser.parse("bytes=-1",size).start()).isEqualTo(size-1);
            assertThat(MediaRangeParser.parse("bytes=-"+size,size).length()).isEqualTo(size);
            assertThatThrownBy(() -> MediaRangeParser.parse("bytes="+size+"-",size)).isInstanceOf(ApiFailureException.class);
            if(size<Long.MAX_VALUE) {
                assertThat(MediaRangeParser.parse("bytes=-"+(size+1),size).length()).isEqualTo(size);
                assertThatThrownBy(() -> MediaRangeParser.parse("bytes="+(size+1)+"-",size)).isInstanceOf(ApiFailureException.class);
            }
        }
        assertThatThrownBy(() -> MediaRangeParser.parse("bytes=0-"+"0".repeat(256),100)).isInstanceOf(ApiFailureException.class);
        assertThatThrownBy(() -> MediaRangeParser.parse(null,0)).isInstanceOf(IllegalArgumentException.class);
    }
}
