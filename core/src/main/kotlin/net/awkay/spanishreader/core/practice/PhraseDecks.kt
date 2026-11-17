package net.awkay.spanishreader.core.practice

import kotlin.random.Random

/*
 * Say-it / listen practice with fixed, hand-checked sentences (exported to fixtures/phrases.json for the web app).
 * "Say it": the English sentence, then the Spanish one with the target words blank; the learner says the answer out
 * loud, then "Show me" fills it in. "Listen": the Spanish sentence is played; the learner translates it in their head,
 * then "Show answer" shows both sentences. Nothing is graded or recorded.
 */

/** One sentence. [spanish] has [Drill.BLANK] where [answer] goes; [verb]/[tense] only in the verb deck. */
data class PhraseCard(
    val id: String,
    val english: String,
    val spanish: String,
    val answer: String,
    val verb: String? = null,
    val tense: String? = null,
    /** A short tip shown with the answer ("tener que + infinitive = have to"). */
    val note: String? = null,
) {
    val filled: String get() = spanish.replace(Drill.BLANK, answer)
    val tenseLabel: String? get() = tense?.let { VerbTenses.byId(it)?.label ?: it }

    /** "tener · present" for the verb deck; null for question words. */
    val hint: String? get() = verb?.let { v -> listOfNotNull(v, tenseLabel).joinToString(" · ") }
}

enum class PhraseDeck(val id: String, val title: String) {
    QUESTIONS("questions", "Question words"),
    VERBS("verbs", "Everyday verbs"),
    ;

    val cards: List<PhraseCard> get() = if (this == QUESTIONS) PhraseDecks.QUESTIONS else PhraseDecks.VERBS

    companion object {
        fun byId(id: String): PhraseDeck? = entries.firstOrNull { it.id == id }
    }
}

enum class PhraseMode(val id: String, val label: String) {
    SAY("say", "Say it"),
    LISTEN("listen", "Listen"),
    ;

    companion object {
        fun byId(id: String): PhraseMode? = entries.firstOrNull { it.id == id }
    }
}

object PhraseDecks {
    /** A practice session: the whole deck in random order. */
    fun session(deck: PhraseDeck, random: Random = Random): List<PhraseCard> = deck.cards.shuffled(random)

    private class Builder(private val prefix: String) {
        val cards = mutableListOf<PhraseCard>()

        fun q(english: String, spanish: String, answer: String, note: String? = null) {
            cards += PhraseCard("$prefix#${cards.size + 1}", english, spanish, answer, note = note)
        }

        fun v(verb: String, tense: String, english: String, spanish: String, answer: String, note: String? = null) {
            cards += PhraseCard("$prefix#${cards.size + 1}", english, spanish, answer, verb, tense, note)
        }
    }

    val QUESTIONS: List<PhraseCard> = Builder("questions").apply {
        q("What's your name?", "¿___ te llamas?", "Cómo")
        q("How are you?", "¿___ estás?", "Cómo")
        q("How do you say \"spoon\" in Spanish?", "¿___ se dice \"spoon\" en español?", "Cómo")
        q("How do I get to the bus station?", "¿___ llego a la estación de autobuses?", "Cómo")
        q("How do you spell your name?", "¿___ se escribe tu nombre?", "Cómo")
        q("How did the trip go?", "¿___ te fue en el viaje?", "Cómo")
        q("How was your weekend?", "¿___ tu fin de semana?", "Qué tal", "qué tal = how is / how was, very common")
        q("What is this?", "¿___ es esto?", "Qué")
        q("What do you want to eat?", "¿___ quieres comer?", "Qué")
        q("What time is it?", "¿___ hora es?", "Qué")
        q("What are you doing?", "¿___ estás haciendo?", "Qué")
        q("What does \"mesero\" mean?", "¿___ significa \"mesero\"?", "Qué")
        q("What happened?", "¿___ pasó?", "Qué")
        q("What kind of music do you like?", "¿___ tipo de música te gusta?", "Qué")
        q("What did you say?", "¿___ dijiste?", "Qué")
        q("What are you looking for?", "¿___ buscas?", "Qué")
        q("What's the weather like?", "¿___ tiempo hace?", "Qué")
        q("Which bus goes downtown?", "¿___ autobús va al centro?", "Qué", "before a noun, \"which\" is usually qué")
        q("Which shoes do you like more?", "¿___ zapatos te gustan más?", "Qué", "before a noun, \"which\" is usually qué")
        q("What do you do for a living?", "¿___ te dedicas?", "A qué")
        q("What are they talking about?", "¿___ están hablando?", "De qué")
        q("Who is that man?", "¿___ es ese señor?", "Quién")
        q("Who wants coffee?", "¿___ quiere café?", "Quién")
        q("Who are your friends?", "¿___ son tus amigos?", "Quiénes", "quiénes when you expect more than one person")
        q("Who called you?", "¿___ te llamó?", "Quién")
        q("Who is it? (at the door)", "¿___ es?", "Quién")
        q("Whose phone is this?", "¿___ es este celular?", "De quién", "whose = de quién")
        q("Whose idea was it?", "¿___ fue la idea?", "De quién", "whose = de quién")
        q("Who are you going with?", "¿___ vas?", "Con quién")
        q("Who did you talk to?", "¿___ hablaste?", "Con quién")
        q("Who is this gift for?", "¿___ es este regalo?", "Para quién")
        q("Where is the bathroom?", "¿___ está el baño?", "Dónde")
        q("Where do you live?", "¿___ vives?", "Dónde")
        q("Where can I buy tickets?", "¿___ puedo comprar boletos?", "Dónde")
        q("Where did you get that shirt?", "¿___ conseguiste esa camisa?", "Dónde")
        q("Where should we meet?", "¿___ nos vemos?", "Dónde")
        q("Where are you going?", "¿___ vas?", "Adónde", "going somewhere: adónde (also written a dónde)")
        q("Where does this bus go?", "¿___ va este autobús?", "Adónde", "going somewhere: adónde (also written a dónde)")
        q("Where are you from?", "¿___ eres?", "De dónde")
        q("Where is your family from?", "¿___ es tu familia?", "De dónde")
        q("When is your birthday?", "¿___ es tu cumpleaños?", "Cuándo")
        q("When did you arrive?", "¿___ llegaste?", "Cuándo")
        q("When are we leaving?", "¿___ nos vamos?", "Cuándo")
        q("When did you learn Spanish?", "¿___ aprendiste español?", "Cuándo")
        q("Since when have you worked here?", "¿___ trabajas aquí?", "Desde cuándo")
        q("What time does the movie start?", "¿___ empieza la película?", "A qué hora", "at what time = a qué hora")
        q("What time do you get up?", "¿___ te levantas?", "A qué hora", "at what time = a qué hora")
        q("What time does the store open?", "¿___ abre la tienda?", "A qué hora", "at what time = a qué hora")
        q("How much is it?", "¿___ cuesta?", "Cuánto")
        q("How much are the avocados?", "¿___ cuestan los aguacates?", "Cuánto")
        q("How much do I owe you?", "¿___ le debo?", "Cuánto")
        q("How much milk do you want?", "¿___ leche quieres?", "Cuánta", "cuánto agrees with the noun: cuánta leche")
        q("How much water do you drink a day?", "¿___ agua tomas al día?", "Cuánta", "agua is feminine: cuánta agua")
        q("How many brothers and sisters do you have?", "¿___ hermanos tienes?", "Cuántos")
        q("How many people are coming?", "¿___ personas vienen?", "Cuántas", "cuánto agrees with the noun: cuántas personas")
        q("How many times have you been to Mexico?", "¿___ veces has ido a México?", "Cuántas")
        q("How old are you?", "¿___ años tienes?", "Cuántos", "age: ¿cuántos años tienes? (literally \"how many years do you have\")")
        q("How long have you lived here?", "¿___ tiempo llevas viviendo aquí?", "Cuánto")
        q("How long does the trip take?", "¿___ dura el viaje?", "Cuánto")
        q("How often do you go to the gym?", "¿___ vas al gimnasio?", "Cada cuánto", "also: con qué frecuencia")
        q("How far is the airport?", "¿___ está el aeropuerto?", "Qué tan lejos", "qué tan + adjective = how (far, big, ...)")
        q("How big is the house?", "¿___ grande es la casa?", "Qué tan", "qué tan + adjective = how (far, big, ...)")
        q("Which one do you want?", "¿___ quieres?", "Cuál")
        q("Which ones are yours?", "¿___ son los tuyos?", "Cuáles")
        q("What is your phone number?", "¿___ es tu número de teléfono?", "Cuál", "\"what is\" + noun is usually cuál es")
        q("What's the difference?", "¿___ es la diferencia?", "Cuál", "\"what is\" + noun is usually cuál es")
        q("Why are you sad?", "¿___ estás triste?", "Por qué")
        q("Why didn't you come?", "¿___ no viniste?", "Por qué")
        q("Why not?", "¿___ no?", "Por qué", "the answer \"because\" is porque, one word")
        q("What is this for?", "¿___ sirve esto?", "Para qué", "para qué = what for (purpose)")
        q("What do you need the money for?", "¿___ necesitas el dinero?", "Para qué", "para qué = what for (purpose)")
    }.cards

    val VERBS: List<PhraseCard> = Builder("verbs").apply {
        // hacer: do, make, weather
        v("hacer", "present", "What are you doing tonight?", "¿Qué ___ esta noche?", "haces")
        v("hacer", "present", "I do my homework after dinner.", "___ la tarea después de cenar.", "Hago")
        v("hacer", "preterite", "We made a cake for her birthday.", "___ un pastel para su cumpleaños.", "Hicimos")
        v("hacer", "preterite", "What did you do yesterday?", "¿Qué ___ ayer?", "hiciste")
        v("hacer", "present", "It's very hot today.", "Hoy ___ mucho calor.", "hace", "weather uses hacer: hace calor, hace frío")
        v("hacer", "near-future", "I'm going to make dinner.", "___ la cena.", "Voy a hacer")
        v("hacer", "present-perfect", "Have you done the shopping yet?", "¿Ya ___ las compras?", "has hecho", "hecho is irregular")
        v("hacer", "imperative", "Do your homework, please. (to a child)", "___ la tarea, por favor.", "Haz", "tú command of hacer: haz")
        v("hacer", "imperfect", "When I was a kid, I played a lot of sports.", "De niño, ___ mucho deporte.", "hacía")
        // tener: have, have to, age, feelings
        v("tener", "present", "I have two kids.", "___ dos hijos.", "Tengo")
        v("tener", "present", "Do you have time?", "¿___ tiempo?", "Tienes")
        v("tener", "present", "We have to go.", "___ que irnos.", "Tenemos", "tener que + infinitive = have to")
        v("tener", "present", "I'm hungry.", "___ hambre.", "Tengo", "hunger, thirst, cold, fear: tener hambre, sed, frío, miedo")
        v("tener", "present", "My son is ten years old.", "Mi hijo ___ diez años.", "tiene", "age uses tener")
        v("tener", "present", "They're right.", "___ razón.", "Tienen", "to be right = tener razón")
        v("tener", "preterite", "She had a cold last week.", "___ un resfriado la semana pasada.", "Tuvo")
        v("tener", "imperfect", "We had a dog when I was little.", "___ un perro cuando yo era chico.", "Teníamos")
        v("tener", "near-future", "You're going to have to wait. (formal)", "___ que esperar.", "Va a tener")
        // haber: the helper (have done) and hay (there is)
        v("haber", "present-perfect", "I have never been to Spain.", "Nunca ___ a España.", "he ido", "have + done = haber + participle")
        v("haber", "present-perfect", "Have you seen my keys?", "¿___ mis llaves?", "Has visto")
        v("haber", "present-perfect", "We have finished the work.", "___ el trabajo.", "Hemos terminado")
        v("haber", "present-perfect", "She hasn't arrived yet.", "Todavía no ___.", "ha llegado")
        v("haber", "present-perfect", "Have you all eaten yet?", "¿Ya ___?", "han comido")
        v("haber", "present", "There is a pharmacy on the corner.", "___ una farmacia en la esquina.", "Hay", "there is / there are = hay")
        v("haber", "present", "Is there a bank near here?", "¿___ un banco cerca de aquí?", "Hay", "there is / there are = hay")
        v("haber", "imperfect", "There were a lot of people at the party.", "___ mucha gente en la fiesta.", "Había", "there was / there were = había")
        v("haber", "near-future", "There's going to be a lot of traffic.", "___ mucho tráfico.", "Va a haber")
        // tomar: take, drink
        v("tomar", "present", "I take the bus to work.", "___ el autobús para ir al trabajo.", "Tomo")
        v("tomar", "present", "We drink coffee every morning.", "___ café todas las mañanas.", "Tomamos", "tomar also means to drink")
        v("tomar", "preterite", "She took a lot of photos.", "___ muchas fotos.", "Tomó")
        v("tomar", "imperative", "Take the medicine with food. (formal)", "___ la medicina con comida.", "Tome", "usted command: -ar verbs end in -e")
        v("tomar", "imperative", "Let's take a taxi.", "___ un taxi.", "Tomemos", "let's = nosotros command")
        v("tomar", "near-future", "I'm going to take a nap.", "___ una siesta.", "Voy a tomar")
        // ir: go
        v("ir", "present", "I'm going to the store.", "___ a la tienda.", "Voy")
        v("ir", "present", "Are you going to the party tomorrow?", "¿___ a la fiesta mañana?", "Vas")
        v("ir", "preterite", "We went to the beach on Sunday.", "___ a la playa el domingo.", "Fuimos", "ir and ser share the preterite: fui, fuiste, fue...")
        v("ir", "preterite", "Where did you go on vacation?", "¿Adónde ___ de vacaciones?", "fuiste")
        v("ir", "imperfect", "They used to go to church every Sunday.", "___ a misa todos los domingos.", "Iban")
        v("ir", "imperative", "Let's go!", "¡___!", "Vamos")
        v("ir", "imperative", "Go to the second floor. (formal)", "___ al segundo piso.", "Vaya")
        v("irse", "present", "I'm leaving now.", "Ya me ___.", "voy", "irse = to leave")
        // get: conseguir, llegar, recibir, ponerse/enojarse
        v("conseguir", "preterite", "I got a new job.", "___ un trabajo nuevo.", "Conseguí", "get (obtain) = conseguir")
        v("conseguir", "preterite", "Did you get the tickets?", "¿___ los boletos?", "Conseguiste", "get (obtain) = conseguir")
        v("conseguir", "present", "We can't get a taxi.", "No ___ taxi.", "conseguimos", "get (obtain) = conseguir")
        v("llegar", "preterite", "I got home late.", "___ tarde a la casa.", "Llegué", "get (somewhere) = llegar")
        v("llegar", "present", "What time do you get to work?", "¿A qué hora ___ al trabajo?", "llegas", "get (somewhere) = llegar")
        v("recibir", "preterite", "I got a message from my boss.", "___ un mensaje de mi jefe.", "Recibí", "get (receive) = recibir")
        v("enojarse", "preterite", "She got very angry.", "Se ___ mucho.", "enojó", "get angry = enojarse")
        v("cansarse", "present-progressive", "I'm getting tired.", "Me ___.", "estoy cansando", "getting + adjective is often a reflexive verb")
        // pedir: order, ask for
        v("pedir", "present", "I'll have the chicken. (ordering)", "Yo ___ el pollo.", "pido", "order (food) = pedir")
        v("pedir", "preterite", "What did you order?", "¿Qué ___?", "pediste")
        v("pedir", "near-future", "We're going to order a pizza.", "___ una pizza.", "Vamos a pedir")
        v("pedir", "present", "She always orders the same thing.", "Siempre ___ lo mismo.", "pide", "e→i: pido, pides, pide")
        v("pedir", "imperative", "Ask for the check, please.", "___ la cuenta, por favor.", "Pide")
        v("pedir", "preterite", "I asked him for help.", "Le ___ ayuda.", "pedí", "ask for something = pedir; ask a question = preguntar")
        // ser: what something is
        v("ser", "present", "I'm a teacher.", "___ maestro.", "Soy", "ser: who or what you are (profession, origin)")
        v("ser", "present", "We're from Texas.", "___ de Texas.", "Somos", "ser: origin")
        v("ser", "present", "It's one o'clock.", "___ la una.", "Es", "time of day uses ser: es la una, son las dos")
        v("ser", "present", "It's two thirty.", "___ las dos y media.", "Son", "time of day uses ser: es la una, son las dos")
        v("ser", "present", "These are my friends.", "Estos ___ mis amigos.", "son")
        v("ser", "present", "Are you Mrs. López? (formal)", "¿___ la señora López?", "Es")
        v("ser", "preterite", "The party was on Saturday.", "La fiesta ___ el sábado.", "fue", "events (when/where they happen) use ser")
        v("ser", "imperfect", "My grandfather was very tall.", "Mi abuelo ___ muy alto.", "era", "describing someone in the past: imperfect")
        v("ser", "near-future", "It's going to be a great day.", "___ un gran día.", "Va a ser")
        // estar: how and where something is
        v("estar", "present", "How are you? (formal)", "¿Cómo ___?", "está")
        v("estar", "present", "I'm tired.", "___ cansado.", "Estoy", "estar: how you are right now")
        v("estar", "present", "Where are my keys?", "¿Dónde ___ mis llaves?", "están", "estar: where something is")
        v("estar", "present", "The soup is cold.", "La sopa ___ fría.", "está", "estar: a state, not what it is")
        v("estar", "present", "How much are the avocados?", "¿A cuánto ___ los aguacates?", "están", "prices that change: ¿a cuánto está(n)...?")
        v("estar", "preterite", "We were at home all day.", "___ en casa todo el día.", "Estuvimos")
        v("estar", "imperfect", "The store was closed.", "La tienda ___ cerrada.", "estaba")
        v("estar", "present-progressive", "She's talking on the phone.", "___ por teléfono.", "Está hablando", "estar + -ando/-iendo = right now")
        v("estar", "past-progressive", "I was sleeping when you called.", "___ cuando llamaste.", "Estaba durmiendo")
        v("estar", "present-progressive", "What are you doing?", "¿Qué ___?", "estás haciendo")
        // encontrar: find
        v("encontrar", "present", "I can't find my phone.", "No ___ mi celular.", "encuentro", "o→ue: encuentro, encuentras, encuentra")
        v("encontrar", "preterite", "We found a good restaurant.", "___ un buen restaurante.", "Encontramos")
        v("encontrar", "preterite", "Did you find what you were looking for?", "¿___ lo que buscabas?", "Encontraste")
        v("encontrar", "near-future", "You'll find it on the second floor. (formal)", "Lo ___ en el segundo piso.", "va a encontrar")
        // poder, querer, necesitar
        v("poder", "present", "Can you help me?", "¿Me ___ ayudar?", "puedes")
        v("poder", "present", "Can I pay with a card?", "¿___ pagar con tarjeta?", "Puedo")
        v("poder", "preterite", "I couldn't sleep last night.", "No ___ dormir anoche.", "pude")
        v("querer", "present", "I want a coffee, please.", "___ un café, por favor.", "Quiero")
        v("querer", "present", "Do you want to come with us?", "¿___ venir con nosotros?", "Quieres")
        v("querer", "imperfect-subjunctive", "I'd like a table for two.", "___ una mesa para dos.", "Quisiera", "quisiera = I'd like (polite)")
        v("querer", "imperfect", "She didn't want to go.", "No ___ ir.", "quería")
        v("gustar", "conditional", "I'd like to go with you.", "Me ___ ir contigo.", "gustaría", "me gustaría = I would like")
        v("gustar", "present", "Do you like the food?", "¿Te ___ la comida?", "gusta", "gustar agrees with the thing liked")
        v("gustar", "preterite", "I really liked the movie.", "Me ___ mucho la película.", "gustó")
        v("necesitar", "present", "I need to rest.", "___ descansar.", "Necesito")
        v("necesitar", "present", "Do you need anything?", "¿___ algo?", "Necesitas")
        // other everyday verbs
        v("dar", "preterite", "I gave her flowers.", "Le ___ flores.", "di")
        v("decir", "preterite", "What did he say?", "¿Qué ___?", "dijo")
        v("decir", "present", "I always tell the truth.", "Siempre ___ la verdad.", "digo")
        v("poner", "preterite", "Where did you put the keys?", "¿Dónde ___ las llaves?", "pusiste")
        v("salir", "present", "We leave at seven.", "___ a las siete.", "Salimos")
        v("salir", "preterite", "She went out with her friends last night.", "___ con sus amigas anoche.", "Salió")
        v("venir", "present", "Are you coming tomorrow?", "¿___ mañana?", "Vienes")
        v("venir", "preterite", "They came by bus.", "___ en autobús.", "Vinieron")
        v("venir", "imperative", "Come here!", "¡___ aquí!", "Ven", "tú command of venir: ven")
        v("ver", "preterite", "Did you see the game?", "¿___ el partido?", "Viste")
        v("ver", "future", "We'll see.", "Ya ___.", "veremos")
        v("saber", "present", "I don't know.", "No ___.", "sé")
        v("saber", "present", "Do you know where the station is?", "¿___ dónde está la estación?", "Sabes", "know a fact = saber; know a person or place = conocer")
        v("conocer", "present", "I know Ana.", "___ a Ana.", "Conozco", "know a person or place = conocer")
        v("llevar", "present", "I'll take it. (buying something)", "Me lo ___.", "llevo", "llevarse = take (buy) it")
        v("comprar", "preterite", "We bought a new car.", "___ un carro nuevo.", "Compramos")
        v("pagar", "near-future", "I'm going to pay in cash.", "___ en efectivo.", "Voy a pagar")
        v("pagar", "preterite", "Did you pay already?", "¿Ya ___?", "pagaste")
        v("llamar", "present", "I'll call you tomorrow.", "Te ___ mañana.", "llamo", "the present often means a near future")
        v("esperar", "imperative", "Wait a moment, please. (formal)", "___ un momento, por favor.", "Espere")
        v("doblar", "imperative", "Turn left. (formal)", "___ a la izquierda.", "Doble")
        v("preocuparse", "negative-imperative", "Don't worry.", "No te ___.", "preocupes", "negative tú commands use the subjunctive")
        v("tocar", "negative-imperative", "Don't touch that!", "¡No ___ eso!", "toques", "negative tú commands use the subjunctive")
        v("vivir", "imperfect", "Back then I was living in Chicago.", "En ese tiempo ___ en Chicago.", "vivía")
    }.cards
}
