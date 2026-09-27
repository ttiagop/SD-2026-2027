import java.net.*;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UDPServer {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        // Estado do Servidor
        int L = 0;
        Map<Integer, String> dicionario = new HashMap<>();
        List<String> historico = new ArrayList<>();

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

                        if (N == L + 1) {
                            List<String> entreguesNestePasso = new ArrayList<>();

                            // 1. Entrega a mensagem atual
                            L = N;
                            entreguesNestePasso.add(texto);
                            historico.add(texto);

                            // 2. Verifica se tem as mensagens seguintes guardadas no dicionário
                            while (dicionario.containsKey(L + 1)) {
                                L++;
                                String textoGuardado = dicionario.get(L);
                                entreguesNestePasso.add(textoGuardado);
                                historico.add(textoGuardado);
                                dicionario.remove(L);
                            }

                            System.out.println("Mensagens entregues neste passo: " + entreguesNestePasso);

                            // Responde ao cliente
                            DatagramPacket reply = new DatagramPacket(request.getData(), request.getLength(), request.getAddress(), request.getPort());
                            aSocket.send(reply);

                        } else if (N > L + 1) {
                            // Guarda no dicionário se vier adiantada
                            dicionario.put(N, texto);
                            System.out.println("Dicionario temporario: " + dicionario);

                            String respostaErro = "waitingfor," + (L + 1);
                            byte[] errBytes = respostaErro.getBytes();
                            DatagramPacket reply = new DatagramPacket(errBytes, errBytes.length, request.getAddress(), request.getPort());
                            aSocket.send(reply);
                        } else {
                            System.out.println("Mensagem atrasada/duplicada descartada.");
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
}