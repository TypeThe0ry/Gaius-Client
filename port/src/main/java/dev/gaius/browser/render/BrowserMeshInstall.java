package dev.gaius.browser.render;

/**
 * A finished terrain mesh produced off the main thread (a mesh Worker result) waiting to be
 * installed into the section heaps by {@link BrowserMeshInstallQueue}.
 *
 * <p>The producer stamps each result with the request it answers: the section node, the
 * request sequence returned by {@link BrowserMeshInstallQueue#beginRequest} and the level and
 * resource epochs current when the request was made. The queue installs a result only while
 * all three still match, so a result for a section that was re-requested, a level that was
 * left or a resource pack that was replaced is discarded instead of drawn.</p>
 */
public interface BrowserMeshInstall {
    /** Packed SectionPos of the section this mesh belongs to. */
    long sectionNode();

    /** Sequence returned by {@link BrowserMeshInstallQueue#beginRequest} for this request. */
    int requestSeq();

    /** {@link BrowserMeshInstallQueue#levelEpoch()} when the request was made. */
    int levelEpoch();

    /** {@link BrowserMeshInstallQueue#resourceEpoch()} when the request was made. */
    int resourceEpoch();

    /** Bytes this install writes to the GPU (vertex plus index data), for the frame budget. */
    int byteSize();

    /**
     * Installs the mesh (allocate heap ranges, write the bytes straight into them, publish the
     * section mesh). Returns false when it cannot be installed yet (for example the heaps are
     * full); it is then retried in a later frame.
     */
    boolean install();

    /** Releases the result's storage without installing it; {@code reason} is a DISCARD_ code. */
    void discard(int reason);
}
