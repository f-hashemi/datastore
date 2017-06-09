package io.opentraffic.datastore.sink;

import com.google.flatbuffers.FlatBufferBuilder;
import io.opentraffic.datastore.BucketSize;
import io.opentraffic.datastore.Measurement;
import io.opentraffic.datastore.TimeBucket;
import io.opentraffic.datastore.VehicleType;
import io.opentraffic.datastore.flatbuffer.Entry;
import io.opentraffic.datastore.flatbuffer.Histogram;
import io.opentraffic.datastore.flatbuffer.Segment;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

/**
 * FlatBufferSink formats a set of measurements to an OutputStream using FlatBuffers.
 */
public class FlatBufferSink implements FileSink {
    private final OutputStream m_output;

    public FlatBufferSink(OutputStream output) {
        this.m_output = output;
    }

    @Override
    public void write(ArrayList<Measurement> measurements) throws IOException {
        final int numMeasurements = measurements.size();
        if (numMeasurements > 0) {
            VehicleType firstVehicleType = measurements.get(0).vehicleType;
            VehicleType lastVehicleType = measurements.get(numMeasurements - 1).vehicleType;
            // at this point, a histogram file contains only one type of vehicle
            assert (firstVehicleType == lastVehicleType);
            // which is okay, since only one type of vehicle is supported.
            assert (firstVehicleType == VehicleType.AUTO);

            byte[] buffer = buildHistogram(firstVehicleType, measurements);

            this.m_output.write(buffer);
        }

        this.m_output.close();
    }

    private static byte[] buildHistogram(VehicleType vehicleType, ArrayList<Measurement> measurements) {
        final int numMeasurements = measurements.size();
        final long maxSegmentId = measurements.get(numMeasurements - 1).segmentId;
        // make sure segment IDs can fit into integers
        assert(maxSegmentId < ((long)Integer.MAX_VALUE));
        final int numSegments = (int)(maxSegmentId + 1);

        FlatBufferBuilder builder = new FlatBufferBuilder();

        // create a single "null" segment to point missing segments at
        Segment.startSegment(builder);
        final int nullSegmentOffset = Segment.endSegment(builder);

        int index = 0;
        long nextSegmentId = 0;
        ArrayList<Integer> segmentOffsets = new ArrayList<>();

        while (index < numMeasurements) {
            final long segmentId = measurements.get(index).segmentId;
            assert(segmentId < (long)Integer.MAX_VALUE);

            while (nextSegmentId < segmentId) {
                segmentOffsets.add(nullSegmentOffset);
                nextSegmentId++;
            }

            final int endIndex = findSegmentIdRange(index, measurements);
            final int numEntries = endIndex - index;

            int[] nextSegmentIds = gatherNextSegmentIds(index, endIndex, measurements);
            // can only handle 256 next segments
            assert(nextSegmentIds.length < 256);
            final int nextSegmentsOffset = Segment.createNextSegmentIdsVector(builder, nextSegmentIds);

            Segment.startEntriesVector(builder, numEntries);
            // have to do this backwards because flatbuffers builds arrays in reverse, starting
            // with the last element and working towards the front. if we want to keep these
            // arrays in the same order, then we need to iterate in reverse.
            for (int i = endIndex - 1; i >= index; i--) {
                Measurement m = measurements.get(i);
                int dayHour = convertTimeBucketToWeek(m.timeBucket);
                int nextSegmentIdx = Arrays.binarySearch(nextSegmentIds, (int)m.nextSegmentId);
                Entry.createEntry(builder, dayHour, nextSegmentIdx, m.durationBucket, m.count);
            }
            final int entriesOffset = builder.endVector();

            Segment.startSegment(builder);
            Segment.addSegmentId(builder, segmentId);
            Segment.addNextSegmentIds(builder, nextSegmentsOffset);
            Segment.addEntries(builder, entriesOffset);
            int segmentOffset = Segment.endSegment(builder);

            segmentOffsets.add(segmentOffset);

            nextSegmentId = segmentId + 1;
            index = endIndex;
        }

        Histogram.startSegmentsVector(builder, numSegments);
        assert(numSegments == segmentOffsets.size());
        // have to do this _backwards_ because the flatbuffer is grown from the "back"
        // towards the "front", so the first call to builder.addOffset will be the
        // last element in the list. the second call will be the second-to-last element
        // and so forth.
        for (int i = numSegments - 1; i >= 0; i--) {
            builder.addOffset(segmentOffsets.get(i).intValue());
        }
        int segmentsOffset = builder.endVector();

        Histogram.startHistogram(builder);
        Histogram.addVehicleType(builder, (byte)vehicleType.ordinal());
        Histogram.addSegments(builder, segmentsOffset);
        int histogramOffset = Histogram.endHistogram(builder);

        Histogram.finishHistogramBuffer(builder, histogramOffset);

        return builder.sizedByteArray();
    }

    private static int convertTimeBucketToWeek(TimeBucket timeBucket) {
        assert(timeBucket.size == BucketSize.HOURLY);
        // guaranteed the result will be smaller than 24*7, so narrowing conversion to
        // int is not a problem.
        return (int)((timeBucket.index - 96L) % (24L * 7L));
    }

    // find the end index such that for measurements[i] where i = index; i < end index; i++
    // gives the set of measurements having the same segment ID.
    private static int findSegmentIdRange(int index, ArrayList<Measurement> measurements) {
        final int numMeasurements = measurements.size();

        long segmentId = measurements.get(index).segmentId;
        index++;

        while ((index < numMeasurements) && (measurements.get(index).segmentId == segmentId)) {
            index++;
        }

        return index;
    }

    // return the (sorted) array list of next segment IDs in the range from beginIndex to
    // endIndex (not inclusive).
    private static int[] gatherNextSegmentIds(int beginIndex, int endIndex, ArrayList<Measurement> measurements) {
        SortedSet<Long> idSet = new TreeSet<>();

        for (int i = beginIndex; i < endIndex; i++) {
            idSet.add(measurements.get(i).nextSegmentId);
        }

        int[] ids = new int[idSet.size()];
        int i = 0;
        for (Long l : idSet) {
            long id = l.longValue();
            assert(id < (long)Integer.MAX_VALUE);
            ids[i] = (int)id;
            i++;
        }
        return ids;
    }
}