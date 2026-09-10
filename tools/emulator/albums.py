# -*- coding: utf-8 -*-
"""
The one album list the emulator fixture is built from.

It used to be two lists — `fetch_covers.py` named twelve albums and
`make_library.py` named seven — which meant five covers were downloaded and never
used, and nothing made the two agree. They are the same fixture, so they are one
list.

**Thirty albums on four shelves, and both halves of that matter.** `WallLayout`
says the camera frames "about three rows", but it builds one row per *shelf* —
`rowCount = shelves.size` — so album count alone buys nothing: thirty albums on
one shelf is one very long row with two thirds of the frame empty, exactly like
seven. The first fixture had seven albums on one shelf, and judging the room's
lighting against it was judging the fixture — 45% of the screen was black because
there was nothing to put there, not because the room is dark. Four shelves is what
puts rows above and below the one being browsed.

Chosen for cover art that differs — dense photography, flat colour fields, white
sleeves, near-black sleeves — because the whole purpose of the shelf strips is to
light *artwork*, and artwork that is all one kind proves nothing.
"""

# (artist, album, year, shelf, [track titles])
ALBUMS = [
    ("Miles Davis", "Kind of Blue", 1959, "JAZZ",
     ["So What", "Freddie Freeloader", "Blue in Green", "All Blues",
      "Flamenco Sketches"]),
    ("Pink Floyd", "The Dark Side of the Moon", 1973, "ROCK & POP",
     ["Speak to Me", "Breathe", "On the Run", "Time",
      "The Great Gig in the Sky", "Money", "Us and Them"]),
    ("Kendrick Lamar", "good kid, m.A.A.d city", 2012, "HIP-HOP & SOUL",
     ["Sherane", "Backseat Freestyle", "The Art of Peer Pressure",
      "Money Trees", "Poetic Justice", "Good Kid"]),
    ("Radiohead", "In Rainbows", 2007, "ROCK & POP",
     ["15 Step", "Bodysnatchers", "Nude", "Weird Fishes", "All I Need",
      "Faust Arp"]),
    ("Portishead", "Dummy", 1994, "ELECTRONIC",
     ["Mysterons", "Sour Times", "Strangers", "It Could Be Sweet",
      "Wandering Star", "Numb"]),
    ("Nina Simone", "Pastel Blues", 1965, "JAZZ",
     ["Be My Husband", "Nobody Knows You", "End of the Line",
      "Trouble in Mind", "Tell Me More"]),
    ("Massive Attack", "Mezzanine", 1998, "ELECTRONIC",
     ["Angel", "Risingson", "Teardrop", "Inertia Creeps", "Exchange",
      "Dissolved Girl"]),
    ("The Weeknd", "Starboy", 2016, "HIP-HOP & SOUL",
     ["Starboy", "Party Monster", "False Alarm", "Reminder", "Rockin",
      "Secrets"]),
    ("Bill Evans", "Sunday at the Village Vanguard", 1961, "JAZZ",
     ["Gloria's Step", "My Man's Gone Now", "Solar", "Alice in Wonderland",
      "All of You"]),
    ("Tyler, the Creator", "Flower Boy", 2017, "HIP-HOP & SOUL",
     ["Foreword", "Where This Flower Blooms", "Sometimes", "See You Again",
      "Who Dat Boy", "911"]),
    ("Paramore", "This Is Why", 2023, "ROCK & POP",
     ["This Is Why", "The News", "Running Out of Time", "C'est Comme Ca",
      "Big Man, Little Dignity"]),
    ("Billy Joel", "Piano Man", 1973, "ROCK & POP",
     ["Travelin' Prayer", "Piano Man", "Ain't No Crime", "Worse Comes to Worst",
      "Captain Jack"]),
    ("Fleetwood Mac", "Rumours", 1977, "ROCK & POP",
     ["Second Hand News", "Dreams", "Never Going Back Again", "Don't Stop",
      "Go Your Own Way", "Songbird"]),
    ("Joni Mitchell", "Blue", 1971, "ROCK & POP",
     ["All I Want", "My Old Man", "Little Green", "Carey", "Blue",
      "California"]),
    ("The Velvet Underground", "The Velvet Underground & Nico", 1967, "ROCK & POP",
     ["Sunday Morning", "I'm Waiting for the Man", "Femme Fatale",
      "Venus in Furs", "Run Run Run"]),
    ("Aphex Twin", "Selected Ambient Works 85-92", 1992, "ELECTRONIC",
     ["Xtal", "Tha", "Pulsewidth", "Ageispolis", "i", "Green Calx"]),
    ("Bjork", "Homogenic", 1997, "ELECTRONIC",
     ["Hunter", "Joga", "Unravel", "Bachelorette", "All Neon Like",
      "5 Years"]),
    ("A Tribe Called Quest", "The Low End Theory", 1991, "HIP-HOP & SOUL",
     ["Excursions", "Buggin' Out", "Rap Promoter", "Butter", "Verses from the Abstract",
      "Check the Rhime"]),
    ("Talking Heads", "Remain in Light", 1980, "ROCK & POP",
     ["Born Under Punches", "Crosseyed and Painless", "The Great Curve",
      "Once in a Lifetime", "Houses in Motion"]),
    ("John Coltrane", "A Love Supreme", 1965, "JAZZ",
     ["Acknowledgement", "Resolution", "Pursuance", "Psalm"]),
    ("Amy Winehouse", "Back to Black", 2006, "HIP-HOP & SOUL",
     ["Rehab", "You Know I'm No Good", "Me & Mr Jones", "Just Friends",
      "Back to Black", "Love Is a Losing Game"]),
    ("Daft Punk", "Discovery", 2001, "ELECTRONIC",
     ["One More Time", "Aerodynamic", "Digital Love", "Harder, Better, Faster, Stronger",
      "Crescendolls", "Nightvision"]),
    ("The Beatles", "Abbey Road", 1969, "ROCK & POP",
     ["Come Together", "Something", "Maxwell's Silver Hammer", "Oh! Darling",
      "Octopus's Garden", "Here Comes the Sun"]),
    ("Frank Ocean", "Blonde", 2016, "HIP-HOP & SOUL",
     ["Nikes", "Ivy", "Pink + White", "Solo", "Skyline To", "Self Control"]),
    ("Burial", "Untrue", 2007, "ELECTRONIC",
     ["Archangel", "Near Dark", "Ghost Hardware", "Endorphin", "Etched Headplate",
      "In McDonalds"]),
    ("Stevie Wonder", "Innervisions", 1973, "HIP-HOP & SOUL",
     ["Too High", "Visions", "Living for the City", "Golden Lady",
      "Higher Ground", "Jesus Children of America"]),
    ("My Bloody Valentine", "Loveless", 1991, "ROCK & POP",
     ["Only Shallow", "Loomer", "Touched", "To Here Knows When", "When You Sleep",
      "Come in Alone"]),
    ("Kraftwerk", "Trans-Europe Express", 1977, "ELECTRONIC",
     ["Europe Endless", "The Hall of Mirrors", "Showroom Dummies",
      "Trans-Europe Express", "Metal on Metal"]),
    ("Erykah Badu", "Baduizm", 1997, "HIP-HOP & SOUL",
     ["Rimshot", "On & On", "Appletree", "Otherside of the Game", "Sometimes",
      "Next Lifetime"]),
    ("Sufjan Stevens", "Illinois", 2005, "ROCK & POP",
     ["Concerning the UFO Sighting", "The Black Hawk War", "Come On! Feel the Illinoise!",
      "John Wayne Gacy, Jr.", "Jacksonville", "Chicago"]),
]


def safe(name):
    """The folder- and file-name spelling of a title. Shared so the covers a fetch
    writes are the covers a library build looks for."""
    keep = " '&()-_,.!"
    return " ".join("".join(c if (c.isalnum() or c in keep) else " "
                            for c in name).split()).strip()
