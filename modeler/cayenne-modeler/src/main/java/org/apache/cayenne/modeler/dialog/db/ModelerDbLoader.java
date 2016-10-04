/*****************************************************************
 *   Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 ****************************************************************/
package org.apache.cayenne.modeler.dialog.db;

import org.apache.cayenne.dbsync.naming.DefaultObjectNameGenerator;
import org.apache.cayenne.dbsync.reverse.db.DbLoader;
import org.apache.cayenne.dbsync.reverse.db.DbLoaderConfiguration;
import org.apache.cayenne.dbsync.reverse.db.DefaultDbLoaderDelegate;
import org.apache.cayenne.dbsync.reverse.filters.CatalogFilter;
import org.apache.cayenne.dbsync.reverse.filters.SchemaFilter;
import org.apache.cayenne.map.DataMap;
import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.Procedure;
import org.apache.cayenne.modeler.dialog.db.model.DBCatalog;
import org.apache.cayenne.modeler.dialog.db.model.DBColumn;
import org.apache.cayenne.modeler.dialog.db.model.DBElement;
import org.apache.cayenne.modeler.dialog.db.model.DBEntity;
import org.apache.cayenne.modeler.dialog.db.model.DBModel;
import org.apache.cayenne.modeler.dialog.db.model.DBProcedure;
import org.apache.cayenne.modeler.dialog.db.model.DBSchema;
import org.apache.cayenne.modeler.dialog.pref.TreeEditor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

// TODO: rewrite this whole thing...
class ModelerDbLoader extends DbLoader {

    private static final Log LOGGER = LogFactory.getLog(ModelerDbLoader.class);

    private TreeEditor treeEditor;
    private ReverseEngineeringController reverseEngineeringController;

    public ModelerDbLoader(ReverseEngineeringController reverseEngineeringController, TreeEditor treeEditor, Connection connection) {
        super(connection,
                reverseEngineeringController.adapter,
                new DefaultDbLoaderDelegate(),
                new DefaultObjectNameGenerator());
        this.treeEditor = treeEditor;
        this.reverseEngineeringController = reverseEngineeringController;
    }

    @Override
    public void load(DataMap dataMap, DbLoaderConfiguration config) throws SQLException {
        Map<String, Procedure> procedureMap = loadProcedures(dataMap, config);
        load(dataMap, config);
        addProcedures(procedureMap);
    }

    @Override
    protected void loadDbEntities(DataMap dataMap, DbLoaderConfiguration config) throws SQLException {

        LOGGER.info("Schema loading...");

        String[] types = getTableTypes(config);

        boolean isNullDetected = false;
        for (CatalogFilter catalog : config.getFiltersConfig().getCatalogs()) {
            for (SchemaFilter schema : catalog.schemas) {
                if (schema.name == null && catalog.name == null) {
                    isNullDetected = true;
                }
            }
        }
        if (isNullDetected) {
            createIfNull(dataMap, config, types);
        } else {
            createIfNotNull(dataMap, config, types);
        }
    }

    private void addProcedures(Map<String, Procedure> procedureMap) throws SQLException {
        DBElement currentDBCatalog;
        DBElement currentDBSchema;
        for (Map.Entry<String, Procedure> procedure : procedureMap.entrySet()) {
            if (supportCatalogs()) {
                String dbCatalogName = procedure.getValue().getCatalog();
                DBElement dbCatalog = reverseEngineeringController.dbModel.getExistingElement(dbCatalogName);
                if (dbCatalog != null) {
                    currentDBCatalog = dbCatalog;
                } else {
                    currentDBCatalog = new DBCatalog(dbCatalogName);
                    reverseEngineeringController.dbModel.addElement(currentDBCatalog);
                }
                if (supportSchemas()) {
                    String dbSchemaName = procedure.getValue().getSchema();
                    DBElement dbSchema = currentDBCatalog.getExistingElement(dbSchemaName);
                    if (dbSchema != null) {
                        currentDBSchema = dbSchema;
                    } else {
                        currentDBSchema = new DBSchema(dbSchemaName);
                        currentDBCatalog.addElement(currentDBSchema);
                    }
                    DBProcedure currentProcedure = new DBProcedure(procedure.getValue().getName());
                    currentDBSchema.addElement(currentProcedure);
                } else {
                    DBProcedure currentProcedure = new DBProcedure(procedure.getValue().getName());
                    currentDBCatalog.addElement(currentProcedure);
                }
            } else if (supportSchemas()) {
                String dbSchemaName = procedure.getValue().getSchema();
                DBElement dbSchema = reverseEngineeringController.dbModel.getExistingElement(dbSchemaName);
                if (dbSchema != null) {
                    currentDBSchema = dbSchema;
                } else {
                    currentDBSchema = new DBSchema(dbSchemaName);
                    reverseEngineeringController.dbModel.addElement(currentDBSchema);
                }
                DBProcedure currentProcedure = new DBProcedure(procedure.getValue().getName());
                currentDBSchema.addElement(currentProcedure);
            }
        }
    }

    private void createIfNotNull(DataMap dataMap, DbLoaderConfiguration config,
                                 String[] types) throws SQLException {
        treeEditor.setRoot(reverseEngineeringController.dataSourceKey);
        reverseEngineeringController.dbModel = new DBModel(reverseEngineeringController.dataSourceKey);
        boolean catalogSetted = false;
        DBElement currentDBCatalog = null;
        DBElement currentDBSchema = null;

        for (CatalogFilter catalog : config.getFiltersConfig().getCatalogs()) {
            for (SchemaFilter schema : catalog.schemas) {
                List<DbEntity> entityList =
                        createTableLoader(catalog.name, schema.name, schema.tables)
                                .loadDbEntities(dataMap, config, types);
                DbEntity entityFromLoader = entityList.get(0);

                if (entityFromLoader != null) {
                    if (!catalogSetted && entityFromLoader.getCatalog() != null) {
                        currentDBCatalog = new DBCatalog(entityFromLoader.getCatalog());
                        reverseEngineeringController.dbModel.addElement(currentDBCatalog);
                        catalogSetted = true;
                    }

                    if (entityFromLoader.getSchema() != null) {
                        currentDBSchema = new DBSchema(entityFromLoader.getSchema());
                        if (currentDBCatalog != null) {
                            currentDBCatalog.addElement(currentDBSchema);
                        } else {
                            reverseEngineeringController.dbModel.addElement(currentDBSchema);
                        }
                    }
                }

                DBEntity currentDBEntity;
                if (currentDBSchema != null) {
                    for (DbEntity dbEntity : entityList) {
                        currentDBEntity = new DBEntity(dbEntity.getName());
                        currentDBSchema.addElement(currentDBEntity);
                        for (DbAttribute dbColumn : dbEntity.getAttributes()) {
                            currentDBEntity.addElement(new DBColumn(dbColumn.getName()));
                        }
                    }
                } else {
                    for (DbEntity dbEntity : entityList) {
                        currentDBEntity = new DBEntity(dbEntity.getName());
                        for (DbAttribute dbColumn : dbEntity.getAttributes()) {
                            currentDBEntity.addElement(new DBColumn(dbColumn.getName()));
                        }
                        currentDBCatalog.addElement(currentDBEntity);
                    }
                }
                currentDBSchema = null;
            }
            catalogSetted = false;
            currentDBCatalog = null;
        }
    }

    private void createIfNull(DataMap dataMap, DbLoaderConfiguration config,
                              String[] types) throws SQLException {

        treeEditor.setRoot(reverseEngineeringController.dataSourceKey);
        reverseEngineeringController.dbModel = new DBModel(reverseEngineeringController.dataSourceKey);
        DBElement currentDBCatalog;
        DBElement currentDBSchema;

        for (CatalogFilter catalog : config.getFiltersConfig().getCatalogs()) {
            for (SchemaFilter schema : catalog.schemas) {
                List<DbEntity> entityList =
                        createTableLoader(catalog.name, schema.name, schema.tables)
                                .loadDbEntities(dataMap, config, types);

                for (DbEntity dbEntity : entityList) {
                    if (supportCatalogs()) {
                        String dbCatalogName = dbEntity.getCatalog();
                        DBElement dbCatalog = reverseEngineeringController.dbModel.getExistingElement(dbCatalogName);
                        if (dbCatalog != null) {
                            currentDBCatalog = dbCatalog;
                        } else {
                            currentDBCatalog = new DBCatalog(dbCatalogName);
                            reverseEngineeringController.dbModel.addElement(currentDBCatalog);
                        }
                        if (supportSchemas()) {
                            String dbSchemaName = dbEntity.getSchema();
                            DBElement dbSchema = currentDBCatalog.getExistingElement(dbSchemaName);
                            if (dbSchema != null) {
                                currentDBSchema = dbSchema;
                            } else {
                                currentDBSchema = new DBSchema(dbSchemaName);
                                currentDBCatalog.addElement(currentDBSchema);
                            }
                            DBEntity currentDBEntity = new DBEntity(dbEntity.getName());
                            currentDBSchema.addElement(currentDBEntity);
                            for (DbAttribute dbColumn : dbEntity.getAttributes()) {
                                currentDBEntity.addElement(new DBColumn(dbColumn.getName()));
                            }
                        } else {
                            DBEntity currentDBEntity = new DBEntity(dbEntity.getName());
                            currentDBCatalog.addElement(currentDBEntity);
                            for (DbAttribute dbColumn : dbEntity.getAttributes()) {
                                currentDBEntity.addElement(new DBColumn(dbColumn.getName()));
                            }
                        }
                    } else {
                        if (supportSchemas()) {
                            String dbSchemaName = dbEntity.getSchema();
                            DBElement dbSchema = reverseEngineeringController.dbModel.getExistingElement(dbSchemaName);
                            if (dbSchema != null) {
                                currentDBSchema = dbSchema;
                            } else {
                                currentDBSchema = new DBSchema(dbSchemaName);
                                reverseEngineeringController.dbModel.addElement(currentDBSchema);
                            }
                            DBEntity currentDBEntity = new DBEntity(dbEntity.getName());
                            currentDBSchema.addElement(currentDBEntity);
                            for (DbAttribute dbColumn : dbEntity.getAttributes()) {
                                currentDBEntity.addElement(new DBColumn(dbColumn.getName()));
                            }
                        }
                    }
                }
            }
        }
    }
}
