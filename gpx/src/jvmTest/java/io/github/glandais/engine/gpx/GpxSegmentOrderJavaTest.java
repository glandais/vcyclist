package io.github.glandais.engine.gpx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.glandais.engine.path.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.Test;

/**
 * Segment ordering, in <b>Java on purpose</b>: every call is written in its shortest form, which
 * is the property under test — invisible from Kotlin, where defaults hide a missing facade.
 */
public class GpxSegmentOrderJavaTest {

    private static GpxDocument af3v() {
        return GpxParserJvm.parse(GpxFixtures.INSTANCE.getUNORDERED_SEGMENTS_GPX());
    }

    @Test
    public void documentIsReorderedWithDefaults() {
        GpxDocument doc = af3v();
        GpxDocument shortForm = GpxSegmentOrderJvm.withOrderedSegments(doc);
        GpxDocument longForm = GpxSegmentOrderJvm.withOrderedSegments(doc, GpxSegmentOrderJvm.segmentOrderOptions());

        assertEquals(longForm, shortForm);
        assertEquals(9, shortForm.getTracks().get(0).getSegments().size());
        assertSame(shortForm, GpxSegmentOrderJvm.withOrderedSegments(shortForm));
        assertSame(doc, GpxSegmentOrderJvm.withOrderedSegments(doc, new SegmentOrderOptions(1.0e9, 16)));
    }

    @Test
    public void trackIsReorderedWithDefaults() {
        GpxTrack track = af3v().getTracks().get(0);
        GpxTrack ordered = GpxSegmentOrderJvm.orderedSegments(track);
        assertEquals(track.getPoints().size(), ordered.getPoints().size());
        assertSame(ordered, GpxSegmentOrderJvm.orderedSegments(ordered, SegmentOrderOptions.Companion.getDEFAULT()));
    }

    @Test
    public void pathsTakeTheOption() {
        GpxDocument doc = af3v();
        Path raw = GpxToPathJvm.tracksAsPaths(doc).get(0);
        Path ordered = GpxToPathJvm.tracksAsPaths(doc, true).get(0);
        Path spelled = GpxToPathJvm.tracksAsPaths(doc, EnumSet.allOf(GpxPathKind.class), true).get(0);

        assertTrue(ordered.getTotalDistance() < raw.getTotalDistance() - 10_000.0);
        assertEquals(ordered.getTotalDistance(), spelled.getTotalDistance(), 1e-9);
        assertEquals(raw.getTotalDistance(), GpxToPathJvm.tracksAsPaths(doc, false).get(0).getTotalDistance(), 1e-9);
        assertEquals(ordered.getTotalDistance(), GpxToPathJvm.firstTrackAsPath(doc, true).getTotalDistance(), 1e-9);
        assertEquals(9, GpxToPathJvm.segmentsAsPaths(doc, true).size());
    }

    @Test
    public void plannerTakesPiecesAlone() {
        List<List<GpxTrackPoint>> pieces = new ArrayList<>();
        for (GpxSegment segment : af3v().getTracks().get(0).getSegments()) {
            pieces.add(segment.getPoints());
        }
        SegmentOrderPlan plan = GpxSegmentOrderJvm.plan(pieces);
        assertEquals(plan, GpxSegmentOrderJvm.plan(pieces, 16));
        assertEquals(9, plan.getOrder().size());
        assertFalse(plan.getReversed().get(plan.getOrder().indexOf(0)));
        assertEquals(plan.getGapM(), GpxSegmentOrderJvm.gapM(pieces, plan.getOrder(), plan.getReversed()), 1e-6);
    }
}
