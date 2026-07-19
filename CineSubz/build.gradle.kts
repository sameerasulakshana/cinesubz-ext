android {
    namespace = "com.cinesubz"
}

version = 2

cloudstream {
    description = "Sinhala subtitled movies and TV shows"
    authors = listOf("cinesubz")

    status = 1

    tvTypes = listOf("Movie", "TvSeries")

    language = "si"

    iconUrl = "https://www.google.com/s2/favicons?domain=cinesubz.net&sz=%size%"
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}
