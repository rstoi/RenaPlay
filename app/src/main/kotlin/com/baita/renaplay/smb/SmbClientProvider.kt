package com.baita.renaplay.smb

/**
 * Uma única instância de [SmbClient] compartilhada entre as telas pós-configuração
 * (browse, episódios, player, busca de legenda). SmbClient já cacheia a sessão SMB
 * autenticada por instância — sem isso, cada tela criava a sua e refazia a
 * autenticação a cada navegação.
 */
object SmbClientProvider {
    val instance = SmbClient()
}
