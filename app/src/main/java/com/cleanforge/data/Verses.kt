package com.yourpackage.cleanforge.data

data class Verse(val text: String, val ref: String)

object Verses {
    val ANCHOR = Verse(
        text = "For God so loved the world, that he gave his one and only Son, " +
               "that whoever believes in him should not perish, but have eternal life.",
        ref = "John 3:16",
    )

    val SUPPORTING = listOf(
        Verse("The Lord God took the man, and put him into the garden of Eden " +
              "to dress it and to keep it.", "Genesis 2:15"),
        Verse("To every thing there is a season… a time to keep, and a time to cast away.",
              "Ecclesiastes 3:1, 6"),
        Verse("Come to me, all you who labor and are heavily burdened, and I will give you rest.",
              "Matthew 11:28"),
        Verse("Be still, and know that I am God.", "Psalm 46:10"),
        Verse("Don't lay up for yourselves treasures on earth… but lay up for yourselves " +
              "treasures in heaven.", "Matthew 6:19–20"),
        Verse("Better is a little with righteousness, than great revenues with injustice.",
              "Proverbs 16:8"),
        Verse("But godliness with contentment is great gain.", "1 Timothy 6:6"),
        Verse("He has shown you, O man, what is good. What does the Lord require of you, " +
              "but to act justly, to love mercy, and to walk humbly with your God?",
              "Micah 6:8"),
        Verse("The earth is the Lord's, and everything in it.", "Psalm 24:1"),
        Verse("Let all things be done decently and in order.", "1 Corinthians 14:40"),
        Verse("Your word is a lamp to my feet, and a light for my path.", "Psalm 119:105"),
        Verse("A faithful man is richly blessed.", "Proverbs 28:20"),
    )

    fun supportingFor(seed: Int): Verse = SUPPORTING[(seed and 0x7FFFFFFF) % SUPPORTING.size]
}
