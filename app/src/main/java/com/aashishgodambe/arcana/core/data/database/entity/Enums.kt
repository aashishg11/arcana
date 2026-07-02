package com.aashishgodambe.arcana.core.data.database.entity

enum class CollectibleCategory { Funko, FigPin, Pokemon }

enum class CollectibleOrigin { HobbyDbImport, PopGrinderImport, ArcanaCapture, Wishlist }

enum class ValueSource { HobbyDbImport, PopGrinderImport, EbayBrowse, PriceCharting, ManualEntry }

enum class SnapshotTrigger { Import, WeeklySync, UserRefresh, ManualEdit }
