from neo4j import GraphDatabase
driver = GraphDatabase.driver('bolt://localhost:7687', auth=('neo4j', 'demo1234'))
with driver.session() as s:
    print('=== registeredAreaCode starts with 11 ===')
    r = s.run('MATCH (c:Company) WHERE any(code IN coalesce(c.registeredAreaCode,[]) WHERE code STARTS WITH "11") RETURN c.companyName AS name, c.registeredAreaCode AS reg ORDER BY name')
    for rec in r:
        print('  -', rec['name'], ' reg=', rec['reg'])

    print()
    print('=== companyName contains 北京 ===')
    r = s.run('MATCH (c:Company) WHERE c.companyName CONTAINS "北京" RETURN c.companyName AS name, c.registeredAreaCode AS reg ORDER BY name')
    for rec in r:
        print('  -', rec['name'], ' reg=', rec['reg'])

    print()
    print('=== total Company count ===')
    print('  ', s.run('MATCH (c:Company) RETURN count(c) AS n').single()['n'])

driver.close()
