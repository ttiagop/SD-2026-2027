import java.net.*;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UDPServer {

    static Map<Integer, String> dicionario = new HashMap<>();
    static List<String> historico = new ArrayList<>();

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        // Estado do Servidor
        int L = 0;

        try {
            aSocket = new DatagramSocket(6789);
            System.out.println("Servidor UDP a escuta na porta 6789...");
            byte[] byteBuffer = new byte[1000];

            while (true) {
                DatagramPacket request = new DatagramPacket(byteBuffer, byteBuffer.length);
                aSocket.receive(request);

                String mensagemRecebida = new String(request.getData(), 0, request.getLength()).trim();
                System.out.println("\nRecebido: " + mensagemRecebida);

                String[] partes = mensagemRecebida.split(",", 2);

                if (partes.length == 2) {
                    try {
                        int N = Integer.parseInt(partes[0].trim());
                        String texto = partes[1].trim();

                        // 2. Chama a nossa função para processar a mensagem e devolve o novo L
                        int novoL = processDeliveredMessages(L, N, texto);

                        // 3. Envia a resposta adequada consoante o que aconteceu
                        if (novoL == L) {
                            // Se o L não mudou, significa que a mensagem chegou adiantada ou atrasada
                            String respostaErro = "waitingfor," + (L + 1);
                            byte[] errBytes = respostaErro.getBytes();
                            DatagramPacket reply = new DatagramPacket(errBytes, errBytes.length, request.getAddress(), request.getPort());
                            aSocket.send(reply);
                        } else {
                            // Se a mensagem foi processada com sucesso (L avançou)
                            L = novoL; // Atualiza o L principal

                            // Responde com echo
                            DatagramPacket reply = new DatagramPacket(request.getData(), request.getLength(), request.getAddress(), request.getPort());
                            aSocket.send(reply);
                        }

                        // Prints fixos em todos os passos
                        System.out.println("A esperar mensagem com N = " + (L + 1));
                        System.out.println("Lista: " + historico);

                    } catch (NumberFormatException e) {
                        System.out.println("Erro: Formato de número inválido.");
                    }
                } else {
                    System.out.println("Erro: Formato de mensagem inválido (falta a vírgula).");
                }
            }
        } catch (SocketException e) {
            System.out.println("Socket: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } finally {
            if (aSocket != null) aSocket.close();
        }
    }


    public static int processDeliveredMessages(int nLastMessageInOrder, int nCurrentMessage, String currentMessage) {
        List<String> entreguesNestePasso = new ArrayList<>();

        // Cenário A: Mensagem em ordem (N == L + 1)
        if (nCurrentMessage == nLastMessageInOrder + 1) {

            // 1. Entrega a mensagem atual
            nLastMessageInOrder++;
            entreguesNestePasso.add(currentMessage);
            historico.add(currentMessage);

            // 2. Verifica se tem as mensagens seguintes guardadas no dicionário (cascata)
            while (dicionario.containsKey(nLastMessageInOrder + 1)) {
                nLastMessageInOrder++;
                String textoGuardado = dicionario.remove(nLastMessageInOrder); // Tira do dicionário
                entreguesNestePasso.add(textoGuardado);
                historico.add(textoGuardado); // Coloca no histórico
            }

            System.out.println("Mensagens entregues neste passo: " + entreguesNestePasso);
        }
        // Cenário B: Mensagem adiantada (N > L + 1)
        else if (nCurrentMessage > nLastMessageInOrder + 1) {
            dicionario.put(nCurrentMessage, currentMessage);
            System.out.println("Dicionario temporario: " + dicionario);
        }
        // Cenário C: Mensagem atrasada ou duplicada
        else {
            System.out.println("Mensagem atrasada/duplicada descartada.");
        }

        // Devolve o L (atualizado ou não) para o main saber como responder
        return nLastMessageInOrder;
    }
}