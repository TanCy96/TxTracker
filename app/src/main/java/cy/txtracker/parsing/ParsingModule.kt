package cy.txtracker.parsing

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Registers every [NotificationParser] into a single set the listener can iterate. Adding a new
 * source (a bank, another wallet) is one new `@Binds @IntoSet` line plus the parser class.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ParsingModule {

    @Binds
    @IntoSet
    abstract fun bindGoogleWalletParser(parser: GoogleWalletParser): NotificationParser
}
