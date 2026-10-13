# 🛠️ Solutions de Proxy Local pour Tests CSP/CORS

L'objectif est de simuler l'existence d'un autre nom de domaine pour des besoins de test (CSP, CORS) lorsque l'application cible tourne localement. Cela est essentiel, car les politiques de sécurité sont gérées par le navigateur, qui observe le nom de domaine de la requête.

Voici deux méthodes principales, classées par kompleksité et par la nature de l'outil utilisé.

---

## 🔬 Option A : Le Proxy Intermédiaire (Exemple avec Node.js)

Cette méthode utilise un petit serveur intermédiaire pour intercepter, réécrire et transférer la requête. C'est la solution la plus "programmable".

### ⚙️ Fonctionnement
Un service (par exemple, un script Node.js) tourne sur un port spécifique (ex: `localhost:3001`). Le frontend appelle ce service au lieu d'appeler directement l'API. Le service proxy lit la requête et la transmet en interne à l'application cible (`localhost:8080`), tout en *maintenant* le nom de domaine simulé.

### 🚀 Mise en place (Exemple Conceptuel)
1.  **Installation :**
    ```bash
    npm init -y
    npm install http-proxy-middleware express
    ```
2.  **Code Proxy (`proxy.js`) :**
    ```javascript
    const express = require('express');
    const { createProxyMiddleware } = require('http-proxy-middleware');
    const app = express();

    // Démarrage du proxy
    app.use('/api', createProxyMiddleware({
      target: 'http://localhost:8080', // 🎯 Adresse réelle de l'API cible
      changeOrigin: true,             // 🔑 Crucial pour simuler le changement d'origine
      rewritePrefix: '/api'
    }));

    app.listen(3001, () => {
      console.log('Proxy en cours d\'écoute sur le port 3001');
    });
    ```
### ✅ Avantages
*   **Contrôle Total :** La logique de proxy peut être personnalisée en fonction des besoins de test.
*   **Simplicité (pour les petits projets) :** Pas de configuration OS complexe.
### ⚠️ Limitations
*   Nécessite une connaissance langagière (ici, Node.js).

---

## 🔩 Option B : Le Proxy Système (Exemple avec Nginx)

C'est la solution la plus robuste et la plus "industrielle" pour la gestion de la réécriture d'en-têtes, gérée directement par le système d'exploitation.

### ⚙️ Fonctionnement
Vous configurez Nginx pour qu'il réponde à un nom de domaine simulé (`test.mondomaine.com`), mais au lieu de chercher un vrai serveur, il redirige (proxy) l'intégralité du trafic vers l'application locale.

### 🚀 Mise en place
1.  **Configuration Nginx :** Créez un fichier de configuration de site (VHost) :
    ```nginx
    server {
        server_name test.mondomaine.com;
        listen 80; 

        location / {
            proxy_pass http://localhost:8080; 
            # Cette ligne est la clé : elle conserve le 'Host' simulé.
            proxy_set_header Host $host; 
        }
    }
    ```
2.  **Correction du Système :** Modifiez le fichier `/etc/hosts` de votre OS pour mapper le nom simulé à l'IP locale (`127.0.0.1`).

### ✅ Avantages
*   **Robustesse :** Outil standard de l'industrie, très performant.
*   **Simplicité de la logique :** La réécriture est gérée par le proxy lui-même.
### ⚠️ Limitations
*   Nécessite d'installer et de configurer un outil de proxy système (Nginx).

---

## 🛑 Conclusion

Pour une solution **simple** qui reste en local, commencez par l'outil de proxy qui correspond à votre environnement de développement actuel (Node.js, Python, etc.). Si vous avez besoin de monter en puissance, passez à **Nginx**.

*N'oubliez jamais : Le proxy résout l'accès, mais les tests CSP et CORS vérifient les *en-têtes* (headers), ce que le proxy doit obligatoirement gérer pour simulé le bon comportement.*