package mixit.web.handler.mailing

import java.time.LocalDateTime
import java.util.Locale
import mixit.MixitProperties
import mixit.model.Mailing
import mixit.model.RecipientType
import mixit.model.RecipientType.Organization
import mixit.model.RecipientType.Sponsor
import mixit.model.RecipientType.Staff
import mixit.model.RecipientType.StaffInPause
import mixit.model.RecipientType.Volunteers
import mixit.model.Role
import mixit.model.User
import mixit.repository.MailingRepository
import mixit.repository.UserRepository
import mixit.util.Cryptographer
import mixit.util.EmailService
import mixit.util.MarkdownConverter
import mixit.util.enumMatcher
import mixit.util.extractFormData
import mixit.util.seeOther
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.ServerResponse.ok
import reactor.core.publisher.Mono

@Component
class MailingHandler(
    val userRepository: UserRepository,
    val cryptographer: Cryptographer,
    val mailingRepository: MailingRepository,
    val markdownConverter: MarkdownConverter,
    val properties: MixitProperties,
    val emailService: EmailService
) {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    private enum class MailingPages(val template: String) {
        LIST("admin-mailing"),
        EDIT("admin-mailing-edit"),
        CONFIRMATION("admin-mailing-confirmation"),
        EMAIL("email-mailing")
    }

    fun listMailing(req: ServerRequest) =
        ok().render(
            MailingPages.LIST.template,
            mapOf(Pair("title", "mailing.title"), Pair("mailings", mailingRepository.findAll()))
        )

    fun createMailing(req: ServerRequest): Mono<ServerResponse> = this.displayMailing()

    fun editMailing(req: ServerRequest) =
        mailingRepository
            .findOne(req.pathVariable("id"))
            .flatMap { displayMailing(it) }

    private fun displayMailing(mailing: Mailing? = null) = ok().render(
        MailingPages.EDIT.template,
        mapOf(
            Pair("title", "mailing.title"),
            Pair("roles", enumMatcher(mailing) { mailing?.type ?: Staff }),
            Pair("mailing", mailing ?: Mailing()),
            Pair("recipientLogins", mailing?.recipientLogins?.joinToString() ?: emptyList<String>())
        )
    )

    fun deleteMailing(req: ServerRequest): Mono<ServerResponse> =
        req.extractFormData().flatMap { formData ->
            mailingRepository
                .deleteOne(formData["id"]!!)
                .then(seeOther("${properties.baseUri}/admin/mailings"))
        }

    fun previewMailing(req: ServerRequest): Mono<ServerResponse> =
        persistMailing((req))
            .flatMap {
                ok().render(
                    MailingPages.EMAIL.template, mapOf(
                        Pair("user", User().copy(firstname = "Bot")),
                        Pair("message", markdownConverter.toHTML(it.content))
                    )
                )
            }

    private fun getUsers(mailing: Mailing): Mono<List<User>> {
        if (mailing.recipientLogins.isNotEmpty()) {
            return userRepository.findAllByIds(mailing.recipientLogins).collectList()
        }
        if (mailing.type != null) {
            return when (mailing.type) {
                Staff -> userRepository.findAllByRole(Role.STAFF).collectList()
                RecipientType.User, Sponsor, StaffInPause, Organization, Volunteers -> Mono.empty()
            }
        }
        return Mono.empty()
    }

    private fun persistMailing(req: ServerRequest): Mono<Mailing> =
        req.extractFormData().flatMap { formData ->
            mailingRepository.save(
                Mailing(
                    id = formData["id"],
                    addedAt = LocalDateTime.parse(formData["addedAt"]),
                    type = formData["recipientType"]?.let { RecipientType.valueOf(it) },
                    title = formData["title"]!!,
                    content = formData["content"]!!,
                    recipientLogins = formData["recipientLogins"]?.split(",") ?: emptyList()
                )
            )
            }

    fun sendMailing(req: ServerRequest): Mono<ServerResponse> =
        persistMailing(req)
            .flatMap { mailing ->
                getUsers(mailing).map { users ->
                    MailingDto(mailing.title, mailing.content, users)
                }
            }
            .flatMap { mailing ->
                mailing.users.forEach { user ->
                    val email = cryptographer.decrypt(user.email)
                    try {
                        logger.info("Send a mailing to $email")
                        emailService.send(
                            MailingPages.EMAIL.template,
                            user,
                            Locale.FRANCE,
                            mailing.title,
                            mapOf(Pair("message", markdownConverter.toHTML(mailing.content)))
                        )
                    } catch (e: Exception) {
                        logger.error("Error on mailing sent to $email", e)
                    }
                }
                ok().render(
                    MailingPages.CONFIRMATION.template, mapOf(
                        Pair("emails", mailing.users.mapNotNull { cryptographer.decrypt(it.email) })
                    )
                )
            }

    fun saveMailing(req: ServerRequest): Mono<ServerResponse> =
        persistMailing((req)).then(seeOther("${properties.baseUri}/admin/mailings"))


}