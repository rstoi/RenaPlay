package com.baita.renaplay.browse

/**
 * Descobre trilogias e franquias dentro da própria biblioteca — "Antes do Amanhecer", "Antes do Pôr
 * do Sol" e "Antes da Meia-Noite" são um filme em três partes, e a grade os mostrava como três
 * estranhos em ordem alfabética.
 *
 * O TMDB tem coleções, mas o backend do Suca Media só expõe busca, biblioteca e legendas — não dá
 * para perguntar a ele a que coleção um filme pertence. Então a sequência é inferida do que se tem:
 * títulos e anos. Agrupa quem começa pela mesma palavra significativa e ordena por ano de lançamento.
 */
object Colecoes {

    /** Palavras que iniciam títulos demais para significar parentesco entre eles. */
    private val VAZIAS = setOf(
        "o", "a", "os", "as", "um", "uma", "the", "an", "de", "do", "da", "dos", "das",
        "el", "la", "le", "les", "il", "der", "die", "das"
    )

    private val ANO = Regex("\\((19\\d{2}|20\\d{2})\\)\\s*$")
    private val SEPARADORES = Regex("[^\\p{L}\\p{N}]+")

    data class Colecao(
        val nome: String,
        val filmes: List<MediaItem>
    ) {
        val total: Int get() = filmes.size
        fun posicaoDe(item: MediaItem): Int = filmes.indexOfFirst { it.path == item.path } + 1
    }

    fun ano(item: MediaItem): Int? = ANO.find(item.title)?.groupValues?.get(1)?.toIntOrNull()

    private fun semAno(titulo: String): String = ANO.replace(titulo, "").trim()

    private fun palavras(titulo: String): List<String> =
        semAno(titulo).lowercase().split(SEPARADORES).filter { it.isNotBlank() }

    /** Primeira palavra que diz algo — o "sobrenome" da franquia. */
    private fun chave(item: MediaItem): String? =
        palavras(item.title).firstOrNull { it !in VAZIAS && it.length >= 3 }

    /**
     * Agrupa os filmes em coleções, ordenadas por ano. Só vira coleção o que tem mais de um filme —
     * um filme sozinho não é uma sequência.
     */
    fun agrupar(itens: List<MediaItem>): List<Colecao> =
        itens.filter { it.kind == MediaKind.MOVIE }
            .groupBy { chave(it) }
            .filterKeys { it != null }
            .filterValues { it.size > 1 }
            .map { (chave, filmes) ->
                val ordenados = filmes.sortedWith(
                    compareBy({ ano(it) ?: Int.MAX_VALUE }, { it.title })
                )
                Colecao(nome = nomeDaColecao(chave!!, ordenados), filmes = ordenados)
            }
            .sortedBy { it.nome }

    /**
     * O nome da coleção é o maior começo que TODOS os filmes compartilham ("O Senhor dos Anéis"),
     * e não a palavra-chave crua — que sozinha ficaria "senhor".
     */
    private fun nomeDaColecao(chave: String, filmes: List<MediaItem>): String {
        val listas = filmes.map { palavras(it.title) }
        val menor = listas.minOf { it.size }
        var comuns = 0
        while (comuns < menor && listas.all { it[comuns] == listas[0][comuns] }) comuns++
        val prefixo = if (comuns > 0) listas[0].take(comuns) else listOf(chave)
        return prefixo.joinToString(" ") { p ->
            if (p in VAZIAS && p != prefixo.first()) p
            else p.replaceFirstChar { it.uppercase() }
        }
    }

    /** A coleção de [item], se ele fizer parte de alguma. */
    fun colecaoDe(item: MediaItem, biblioteca: List<MediaItem>): Colecao? =
        agrupar(biblioteca).firstOrNull { c -> c.filmes.any { it.path == item.path } }
}
