# Common Storage Service

Service commun pour la gestion du stockage centralisé avec quotas par tenant.

## Compilation

**Important** : Ce module dépend de `common-lib`. Vous devez d'abord compiler `common-lib` :

```bash
# Depuis la racine backend/
cd ..
mvn clean install -pl common-lib -am -DskipTests
```

Puis compilez ce module :

```bash
mvn clean compile
```

## Dépendances

- `common-lib` : Pour `SimpleMappingService` et `StrictMappingEngine`
- `spring-boot-starter-webflux` : Pour `WebClient` et la gestion HTTP réactive
- `spring-boot-starter-cache` + `caffeine` : Pour le cache des MappingSpec

## Configuration

Dans `application.yml` :

```yaml
storage:
  mapping:
    enabled: true
    catalog-base-url: http://localhost:8081
    timeout-seconds: 30
```

## Fonctionnalités

- **Storage de données** : JSON, binaires, texte
- **Quotas par tenant** : Gestion automatique des quotas de stockage
- **Mapping automatique** : Résolution de mapping via catalog-service avec cache
- **Colonne data_mapped** : Stockage des données mappées (format JSONB)

