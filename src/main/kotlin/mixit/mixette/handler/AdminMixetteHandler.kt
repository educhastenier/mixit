package mixit.mixette.handler

import mixit.MixitProperties
import mixit.event.handler.AdminEventHandler.Companion.CURRENT_EVENT
import mixit.event.model.EventService
import mixit.mixette.model.MixetteDonation
import mixit.mixette.repository.MixetteDonationRepository
import mixit.security.MixitWebFilter.Companion.SESSION_LOGIN_KEY
import mixit.security.MixitWebFilter.Companion.SESSION_ROLE_KEY
import mixit.security.model.Cryptographer
import mixit.ticket.model.Ticket
import mixit.ticket.model.TicketService
import mixit.ticket.model.TicketType
import mixit.user.model.CachedUser
import mixit.user.model.Role
import mixit.user.model.User
import mixit.user.model.UserService
import mixit.util.extractFormData
import mixit.util.json
import mixit.util.seeOther
import mixit.util.toNumber
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.ServerResponse.ok
import org.springframework.web.reactive.function.server.body
import org.springframework.web.reactive.function.server.queryParamOrNull
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import java.time.Duration

@Component
class AdminMixetteHandler(
    private val repository: MixetteDonationRepository,
    private val userService: UserService,
    private val service: EventService,
    private val ticketService: TicketService,
    private val properties: MixitProperties,
    private val cryptographer: Cryptographer
) {

    companion object {
        const val TEMPLATE_BY_USER_LIST = "admin-mixette-donors"
        const val TEMPLATE_BY_ORGA_LIST = "admin-mixette-organizations"
        const val TEMPLATE_BY_USER = "admin-mixette-donor"
        const val TEMPLATE_BY_ORGA = "admin-mixette-organization"
        const val TEMPLATE_EDIT = "admin-mixette-donation"
        const val LIST_URI_FOR_ADMIN = "/admin/mixette-organization"
        const val LIST_URI_FOR_VOLUNTEER = "/volunteer/mixette-organization"
    }

    fun findAll(req: ServerRequest) = ok().json().body(repository.findAll())

    /**
     * Used to display aggregated data by donors
     */
    fun adminDonorDonations(req: ServerRequest): Mono<ServerResponse> =
        adminGroupDonations(TEMPLATE_BY_USER_LIST) {
            findDonorByEncryptedTicketNumber(it.encryptedTicketNumber!!).block(Duration.ofSeconds(10))
                ?: MixetteUserDonationDto(
                    name = "",
                    login = it.userLogin,
                    email = cryptographer.decrypt(it.encryptedUserEmail)!!,
                    ticketNumber = cryptographer.decrypt(it.encryptedTicketNumber)
                )
        }

    /**
     * Used to display aggregated data by organizations
     */
    fun adminOrganizationDonations(req: ServerRequest): Mono<ServerResponse> =
        adminGroupDonations(TEMPLATE_BY_ORGA_LIST) { donation ->
            userService.findOne(donation.organizationLogin)
                .map {
                    MixetteOrganizationDonationDto(name = it.organizationName, login = it.login)
                }
                .block(Duration.ofSeconds(10)) ?: MixetteOrganizationDonationDto(
                name = "",
                login = donation.organizationLogin
            )
        }

    private fun <T : MixetteDonationDto> adminGroupDonations(
        target: String,
        transformation: (MixetteDonation) -> T
    ): Mono<ServerResponse> {
        val donationByOrgas = repository.findAllByYear(CURRENT_EVENT).collectList().map { donations ->
            donations
                .groupBy { transformation.invoke(it) }
                .map { entry ->
                    entry.key.populate(
                        number = entry.value.size,
                        quantity = entry.value.sumOf { it.quantity },
                        amount = entry.value.sumOf { it.quantity * properties.mixetteValue.toDouble() }
                    )
                }
                .sortedByDescending { it.quantity }
                .mapIndexed { index, donation -> donation.updateRank(index + 1) }
        }
        return ok().render(
            target,
            mapOf(Pair("donations", donationByOrgas), Pair("title", "admin.donations.title"))
        )
    }

    /**
     * Used to display the page to create a new donation
     */
    fun addDonation(req: ServerRequest): Mono<ServerResponse> =
        req.session().flatMap { session ->
            this.adminDonation(MixetteDonation(CURRENT_EVENT, userLogin = session.getAttribute(SESSION_LOGIN_KEY)))
        }

    /**
     * Used to display the page to create a new donation, when a volunteer or a staff member has scanned a badge
     */
    fun addDonationForAttendee(req: ServerRequest): Mono<ServerResponse> =
        ticketService
            .findByNumber(req.pathVariable("number"))
            .flatMap { donor ->
                req.session().flatMap { session ->
                    this.adminDonation(
                        MixetteDonation(
                            year = CURRENT_EVENT,
                            encryptedTicketNumber = cryptographer.encrypt(donor.number),
                            userLogin = donor.login,
                            createdBy = session.getAttribute(SESSION_LOGIN_KEY),
                            encryptedUserEmail = cryptographer.encrypt(donor.email)!!
                        )
                    )
                }
            }

    /**
     * Used to display screen to edit a donation
     */
    fun editDonation(req: ServerRequest): Mono<ServerResponse> =
        repository.findOne(req.pathVariable("id")).flatMap { this.adminDonation(it) }

    /**
     * Used to display aggregated data for an organization
     */
    fun editOrga(req: ServerRequest): Mono<ServerResponse> =
        this.adminGroupDonation(
            TEMPLATE_BY_ORGA,
            repository.findByOrganizationLogin(req.queryParamOrNull("login")!!, CURRENT_EVENT).collectList()
        ) { donation ->
            userService.findOne(donation.organizationLogin)
                .map {
                    MixetteOrganizationDonationDto(
                        name = it.company ?: "${it.firstname} ${it.lastname}",
                        login = it.login
                    )
                }

        }

    /**
     * Used to display aggregated data for a donor
     */
    fun editDonor(req: ServerRequest): Mono<ServerResponse> =
        this.adminGroupDonation(
            TEMPLATE_BY_USER,
            repository.findByTicketNumber(cryptographer.encrypt(req.queryParamOrNull("ticketNumber"))!!, CURRENT_EVENT)
                .collectList()
        ) {
            findDonorByEncryptedTicketNumber(it.encryptedTicketNumber!!)
        }

    private fun <T : MixetteDonationDto> adminGroupDonation(
        target: String,
        monoDonations: Mono<List<MixetteDonation>>,
        transformation: (MixetteDonation) -> Mono<T>
    ): Mono<ServerResponse> =
        monoDonations.flatMap { donations ->
            val quantity = donations.sumOf { it.quantity }
            transformation.invoke(donations.first()).flatMap { donation ->
                val userDonation = donation.populate(
                    number = donations.size,
                    quantity = quantity,
                    amount = quantity * properties.mixetteValue.toDouble()
                )
                ok().render(
                    target,
                    mapOf(
                        Pair("donations", donations.map {
                            MixetteUserDonationDto(
                                name = "",
                                email = cryptographer.decrypt(it.encryptedUserEmail)!!,
                                ticketNumber = cryptographer.decrypt(it.encryptedTicketNumber),
                                login = if(target == TEMPLATE_BY_USER) it.organizationLogin else it.userLogin,
                                id = it.id,
                                quantity = it.quantity
                            )
                        }),
                        Pair("userDonation", userDonation),
                        Pair("title", "admin.donations.title")
                    )
                )
            }
        }

    private fun adminDonation(
        donation: MixetteDonation,
        errors: Map<String, String> = emptyMap()
    ): Mono<ServerResponse> =
        service.findByYear(CURRENT_EVENT.toInt()).flatMap { event ->
            findDonorByEncryptedTicketNumber(donation.encryptedTicketNumber ?: "").flatMap { donor ->
                userService.findOne(donation.organizationLogin)
                    .switchIfEmpty { Mono.just(CachedUser(User(""))) }
                    .flatMap { organization ->
                        ok().render(
                            TEMPLATE_EDIT,
                            mapOf(
                                Pair("creationMode", donation.id == null),
                                Pair(
                                    "donation",
                                    MixetteDonationDetailedDto(
                                        donation = donation,
                                        cryptographer = cryptographer,
                                        username = donor.name,
                                        organizationName = organization.organizationName
                                    )
                                ),
                                Pair("organizations", event.organizations.map { MixetteOrganizationDto(it, donation) }),
                                Pair("errors", errors),
                                Pair("hasErrors", errors.isNotEmpty())
                            )
                        )
                    }
            }
        }

    private fun persistDonation(
        req: ServerRequest,
        donation: MixetteDonation,
        donor: MixetteUserDonationDto,
        receiver: User,
        quantity: Int?,
        errors: Map<String, String>,
    ): Mono<ServerResponse> {
        val newDonation = donation.copy(
            encryptedTicketNumber = cryptographer.encrypt(donor.ticketNumber)!!,
            userLogin = donor.login,
            encryptedUserEmail = cryptographer.encrypt(donor.email)!!,
            organizationLogin = receiver.login,
            quantity = quantity ?: 0
        )
        if (errors.isNotEmpty()) {
            return adminDonation(newDonation, errors)
        }
        return req.session().flatMap { session ->
            val connectedUser = session.getAttribute<String>(SESSION_LOGIN_KEY)
            val userRole = session.getAttribute<Role>(SESSION_ROLE_KEY)

            if (newDonation.id == null) {
                repository.insert(newDonation.copy(createdBy = connectedUser)).flatMap {
                    seeOther("${properties.baseUri}${if (userRole == Role.STAFF) LIST_URI_FOR_ADMIN else LIST_URI_FOR_VOLUNTEER}")
                }
            } else {
                repository.update(newDonation.copy(updatedBy = connectedUser)).flatMap {
                    seeOther("${properties.baseUri}${if (userRole == Role.STAFF) LIST_URI_FOR_ADMIN else LIST_URI_FOR_VOLUNTEER}")
                }
            }
        }
    }

    /**
     * This function parse users and tickets to find people
     */
    private fun findDonorByEncryptedTicketNumber(ticketNumber: String): Mono<MixetteUserDonationDto> =
        // We find the ticket
        ticketService.findByNumber(cryptographer.decrypt(ticketNumber)!!)
            .map { it.toEntity(cryptographer) }
            .switchIfEmpty(Mono.just(Ticket("", "", lastname = "", firstname = "", type = TicketType.ATTENDEE)))
            .flatMap { ticket ->
                // We try to find if user is known
                userService.findOneByEncryptedEmail(ticket.encryptedEmail)
                    .map { it.toUser() }
                    .switchIfEmpty { Mono.just(User("")) }
                    .map { user ->
                        // ticket and user can be null but not the two at the same time
                        MixetteUserDonationDto(
                            name = if (!user?.login.isNullOrEmpty()) "${user.firstname} ${user.lastname}"
                            else ticket?.let {
                                "${cryptographer.decrypt(ticket.firstname)} ${
                                    cryptographer.decrypt(
                                        ticket.lastname
                                    )
                                }"
                            } ?: "",
                            email = cryptographer.decrypt(ticket.encryptedEmail)!!,
                            ticketNumber = cryptographer.decrypt(ticket.number),
                            login = user?.login
                        )
                    }
            }

    fun adminSaveDonation(req: ServerRequest): Mono<ServerResponse> =
        req.extractFormData().flatMap { formData ->
            val organizationLogin: String = formData["organizationLogin"]!!
            val ticketNumber: String = cryptographer.encrypt(formData["ticketNumber"])!!
            userService.findOne(organizationLogin).flatMap { receiver ->
                findDonorByEncryptedTicketNumber(ticketNumber).flatMap { donor ->
                    val errors = mutableMapOf<String, String>()

                    if (donor.login.isNullOrEmpty() && donor.ticketNumber.isNullOrEmpty()) {
                        errors["userLogin"] = "admin.donations.error.userLogin.required"
                    }
                    if (receiver == null) {
                        errors["organizationLogin"] = "admin.donations.error.organizationLogin.required"
                    }
                    val quantity = formData["quantity"]?.toNumber()
                    if (quantity == null || quantity <= 0) {
                        errors["quantity"] = "admin.donations.error.quantity.invalid"
                    }
                    repository
                        .findOne(formData["id"] ?: "")
                        .map { it }
                        .switchIfEmpty { Mono.just(MixetteDonation(CURRENT_EVENT)) }
                        .flatMap {
                            persistDonation(
                                req = req,
                                donation = it,
                                donor = donor.copy(email = donor.email),
                                receiver = receiver.toUser(),
                                quantity = quantity,
                                errors = errors
                            )
                        }
                }
            }
        }

    fun adminDeleteDonation(req: ServerRequest): Mono<ServerResponse> =
        req.extractFormData().flatMap { formData ->
            repository
                .deleteOne(formData["id"]!!)
                .then(seeOther("${properties.baseUri}$LIST_URI_FOR_ADMIN"))
        }
}
