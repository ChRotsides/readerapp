"""Base canonical tag vocabulary (NovelUpdates-style names) and synonyms from other sources.
NovelUpdates' own tag list is imported by its 5b crawler as canonical tags (origin='novelupdates')."""
BASE_TAGS = [
    "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Harem", "Historical", "Horror", "Mystery", "Psychological",
    "Romance", "School Life", "Sci-fi", "Slice of Life", "Supernatural", "Tragedy", "Wuxia", "Xianxia", "Xuanhuan",
    "Reincarnation", "Transported to Another World", "Transmigration", "Cultivation", "Game Elements", "System Administrator",
    "Overpowered Protagonist", "Weak to Strong", "Villainess", "Revenge", "Magic", "Dungeons", "Monsters", "Academy",
    "Female Protagonist", "Male Protagonist", "Military", "Politics", "Cooking", "Time Travel", "Regression",
    "Tower Climbing", "Hunters", "Apocalypse", "Post-apocalyptic", "Virtual Reality", "Adapted to Manga", "Adapted to Anime",
    "Adapted to Manhwa", "Slow Romance", "Strategy", "Business Management", "Medical", "Gods", "Demons", "Dragons",
]
# (synonym as found in a source, canonical)
SYNONYMS = {
    "isekai": "Transported to Another World", "transported to another world": "Transported to Another World",
    "reborn": "Reincarnation", "reincarnated": "Reincarnation", "rebirth": "Reincarnation", "regressor": "Regression",
    "return": "Regression", "returner": "Regression", "overpowered": "Overpowered Protagonist", "op mc": "Overpowered Protagonist",
    "overpowered mc": "Overpowered Protagonist", "level system": "Game Elements", "gamelit": "Game Elements", "litrpg": "Game Elements",
    "game": "Game Elements", "vrmmo": "Virtual Reality", "vr game": "Virtual Reality", "school": "School Life",
    "school life": "School Life", "slice of life": "Slice of Life", "sci fi": "Sci-fi", "science fiction": "Sci-fi",
    "female harem": "Harem", "male harem": "Harem", "reverse harem": "Harem", "villainess": "Villainess", "akuyaku reijou": "Villainess",
    "revenge": "Revenge", "dungeon": "Dungeons", "dungeons": "Dungeons", "monster": "Monsters", "magic": "Magic",
    "post apocalyptic": "Post-apocalyptic", "xianxia": "Xianxia", "cultivation": "Cultivation", "xuanhuan": "Xuanhuan",
    "wuxia": "Wuxia", "romantic comedy": "Romance", "adult cast": "Male Protagonist",
    # Syosetu / JP keywords
    "異世界転生": "Reincarnation", "異世界転移": "Transported to Another World", "異世界": "Transported to Another World",
    "転生": "Reincarnation", "悪役令嬢": "Villainess", "ハーレム": "Harem", "復讐": "Revenge", "ざまぁ": "Revenge",
    "ダンジョン": "Dungeons", "魔法": "Magic", "チート": "Overpowered Protagonist", "最強": "Overpowered Protagonist",
    "学園": "School Life", "恋愛": "Romance", "ファンタジー": "Fantasy", "冒険": "Adventure", "ほのぼの": "Slice of Life",
    "ゲーム": "Game Elements", "vr": "Virtual Reality", "料理": "Cooking", "タイムリープ": "Time Travel", "ループ": "Time Travel",
}
