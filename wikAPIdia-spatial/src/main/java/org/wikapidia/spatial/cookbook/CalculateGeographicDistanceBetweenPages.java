package org.wikapidia.spatial.cookbook;

import com.google.common.collect.Lists;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;
import org.geotools.referencing.GeodeticCalculator;


import org.wikapidia.conf.Configurator;
import org.wikapidia.core.WikapidiaException;
import org.wikapidia.core.cmd.Env;
import org.wikapidia.core.cmd.EnvBuilder;
import org.wikapidia.core.dao.LocalPageDao;
import org.wikapidia.core.dao.MetaInfoDao;
import org.wikapidia.core.lang.LanguageSet;
import org.wikapidia.core.model.LocalPage;
import org.wikapidia.core.model.NameSpace;
import org.wikapidia.core.model.Title;
import org.wikapidia.spatial.core.dao.SpatialDataDao;
import org.wikapidia.wikidata.WikidataDao;

import java.util.List;

/**
 * Created by bjhecht on 4/7/14.
 */
public class CalculateGeographicDistanceBetweenPages {

    public static void main(String[] args){

        try {

            Env env = new EnvBuilder().build();
            Configurator c = env.getConfigurator();

            SpatialDataDao sdDao = c.get(SpatialDataDao.class);
            WikidataDao wdDao = c.get(WikidataDao.class);
            LocalPageDao lpDao = c.get(LocalPageDao.class);

            LanguageSet loadedLangs = lpDao.getLoadedLanguages();


            String[] pageNames = new String[]{"Minneapolis", "Chicago", "Beijing"};

            List<Integer> itemIds = Lists.newArrayList();

            // Find the Wikidata item IDs for the local pages corresponding to the page names
            for (String pageName : pageNames){
                LocalPage localPage = lpDao.getByTitle(new Title(pageName, loadedLangs.getBestAvailableEnglishLang(true)), NameSpace.ARTICLE);
                Integer itemId = wdDao.getItemId(localPage);

                itemIds.add(itemId);
            }

            GeodeticCalculator calc = new GeodeticCalculator();

            // get the geometries for each local page/item id and calculate the distance between them
            for (int i = 0; i < itemIds.size(); i++){
                Geometry g1 = sdDao.getGeometry(itemIds.get(i), "wikidata", "earth");
                Point centroid = g1.getCentroid();
                calc.setStartingGeographicPoint(centroid.getX(), centroid.getY());
                for (int j = i; j < itemIds.size(); j++){
                    Geometry g2 = sdDao.getGeometry(itemIds.get(j), "wikidata", "earth");
                    centroid = g2.getCentroid();
                    calc.setDestinationGeographicPoint(centroid.getX(), centroid.getY());

                    String out = String.format("%s to %s is %fkm", pageNames[i], pageNames[j], calc.getOrthodromicDistance()/1000);
                    System.out.println(out);

                }
            }

        }catch(Exception e){
            e.printStackTrace();;
        }


    }

}