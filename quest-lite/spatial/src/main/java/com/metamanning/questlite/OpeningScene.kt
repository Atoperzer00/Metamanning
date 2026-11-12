package com.metamanning.questlite

/**
 * The first headset view: passthrough plus a few linked thoughts at reading distance.
 * Coordinates use the library frame, where z is extra distance past [PullPolicy.DISTANCE_M].
 * This does not enable a head or hand ray.
 */
object OpeningScene {
    const val VISIBLE = 4
    val PULL_HOME = Vec3(0f, 0f, 0f)

    private val spread = listOf(
        Vec3(0f, 0.12f, 0.7f),
        Vec3(-0.72f, -0.05f, 0.2f),
        Vec3(0.72f, -0.05f, 0.2f),
        Vec3(0.05f, 0.28f, 0.4f),
    )

    fun spread(count: Int): List<Vec3> {
        require(count in 1..spread.size) { "The opening scene shows at most four thoughts" }
        return spread.take(count)
    }

    fun stacked(coordinates: List<Vec3>): Boolean {
        if (coordinates.size < 2) return false
        val origin = coordinates.first()
        return coordinates.all { (it - origin).length() < 0.2f }
    }

    /** Empty library only. Returns ids with the root first, then the thoughts linked to it. */
    fun seed(graph: ThoughtGraph, provenance: (String) -> Provenance): List<String> {
        require(graph.thoughts().isEmpty()) { "The opening library is only seeded when nothing is stored" }
        val spots = spread(VISIBLE)
        val root = graph.create(
            ThoughtType.CONCEPT, "Second brain", "Notes, questions, and the sources that support them.",
            ThoughtPrivacy.SHAREABLE, spots[0], provenance("opening"),
        )
        val reading = graph.create(
            ThoughtType.NOTE, "Reading distance", "Keep a thought about a meter in front of you.",
            ThoughtPrivacy.SHAREABLE, spots[1], provenance("opening"),
        )
        val question = graph.create(
            ThoughtType.TASK, "Open question", "What should this project remember next?",
            ThoughtPrivacy.SHAREABLE, spots[2], provenance("opening"),
        )
        val evidence = graph.create(
            ThoughtType.REFERENCE, "Field note", "A claim stays next to the source that supports it.",
            ThoughtPrivacy.SHAREABLE, spots[3], provenance("opening"),
        )
        graph.link(root.id, reading.id, "related", "", provenance("opening"))
        graph.link(root.id, question.id, "related", "", provenance("opening"))
        graph.link(root.id, evidence.id, "evidence", "field note", provenance("opening"))
        return listOf(root.id, reading.id, question.id, evidence.id)
    }

    fun cardsToOpen(graph: ThoughtGraph, limit: Int = VISIBLE): List<String> {
        require(limit in 1..VISIBLE)
        val all = graph.thoughts()
        if (all.isEmpty()) return emptyList()
        val root = all.maxWith(compareBy({ graph.neighbors(it.id).size }, { it.updatedAt }, { it.id }))
        return (listOf(root.id) + graph.neighbors(root.id).map { it.id } + all.map { it.id }).distinct().take(limit)
    }
}
