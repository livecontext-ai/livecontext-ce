#!/bin/bash

echo "🚀 Demarrage de l'environnement de developpement API Marketplace..."

# Demarrer les services d'infrastructure
echo "📦 Demarrage des services d'infrastructure..."
cd infra
docker-compose up -d postgres redis
cd ..

# Attendre que PostgreSQL soit pret
echo "⏳ Attente que PostgreSQL soit pret..."
until docker exec infra-postgres-1 pg_isready -U postgres; do
  echo "PostgreSQL n'est pas encore pret, attente..."
  sleep 2
done
echo "✅ PostgreSQL est pret !"

# Compiler le projet
echo "🔨 Compilation du projet..."
mvn clean compile -DskipTests

# Demarrer le catalog-service
echo "🏪 Demarrage du catalog-service..."
cd catalog-service
mvn spring-boot:run &
CATALOG_PID=$!
cd ..

# Attendre que le catalog-service soit pret
echo "⏳ Attente que le catalog-service soit pret..."
until curl -s http://localhost:8081/actuator/health > /dev/null; do
  echo "Catalog-service n'est pas encore pret, attente..."
  sleep 5
done
echo "✅ Catalog-service est pret !"

# Demarrer le gateway
echo "🚪 Demarrage du gateway..."
cd gateway
mvn spring-boot:run &
GATEWAY_PID=$!
cd ..

# Attendre que le gateway soit pret
echo "⏳ Attente que le gateway soit pret..."
until curl -s http://localhost:8080/actuator/health > /dev/null; do
  echo "Gateway n'est pas encore pret, attente..."
  sleep 5
done
echo "✅ Gateway est pret !"

echo ""
echo "🎉 Environnement de developpement demarre avec succes !"
echo ""
echo "📊 Services disponibles :"
echo "  - PostgreSQL: localhost:5433"
echo "  - Redis: localhost:6379"
echo "  - Catalog Service: http://localhost:8081"
echo "  - API Gateway: http://localhost:8080"
echo ""
echo "🔧 Endpoints MCP :"
echo "  - Creer un outil: POST http://localhost:8081/api/catalog/tools"
echo "  - Executer un outil: POST http://localhost:8080/api/mcp/{category}/{toolSlug}/execute"
echo ""
echo "⏹️  Pour arreter les services, appuyez sur Ctrl+C"

# Fonction de nettoyage
cleanup() {
  echo ""
  echo "🛑 Arret des services..."
  kill $CATALOG_PID $GATEWAY_PID 2>/dev/null
  cd infra
  docker-compose down
  cd ..
  echo "✅ Services arretes."
  exit 0
}

# Capturer Ctrl+C
trap cleanup SIGINT

# Attendre indefiniment
wait
