package project.rayedchan.utilities;

import Thor.API.Exceptions.tcAPIException;
import Thor.API.Exceptions.tcBulkException;
import Thor.API.Operations.tcExportOperationsIntf;
import Thor.API.Operations.tcImportOperationsIntf;
import com.thortech.xl.dataaccess.tcDataProvider;
import com.thortech.xl.dataaccess.tcDataSet;
import com.thortech.xl.dataaccess.tcDataSetException;
import com.thortech.xl.dataobj.PreparedStatementUtil;
import com.thortech.xl.ddm.exception.DDMException;
import com.thortech.xl.ddm.exception.TransformationException;
import com.thortech.xl.orb.dataaccess.tcDataAccessException;
import com.thortech.xl.vo.ddm.RootObject;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.naming.NamingException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import project.rayedchan.custom.objects.ReconciliationField;
import project.rayedchan.exception.BadFileFormatException;
import project.rayedchan.exception.MissingHeaderException;
import project.rayedchan.exception.MissingRequiredFieldException;
import project.rayedchan.exception.ResourceObjectNameNotFoundException;

/**
 * @author rayedchan
 * This utility allows you to add and delete a reconciliation field 
 * from an object resource. Removal and creation of a reconciliation field 
 * are done at the meta-data level. My utility does not support multivalued 
 * attribute. It is highly recommended to backup the xml for the resource object
 * before using this utility.
 * 
 * TODO: Reverse the attributes of a tag (OIM default format). DOM automatically
 * alphabetize attributes of a tag. 
 * 
 * Note: When using this utility to add reconciliation fields, 
 * the "Create Reconciliation Profile" is initiated by the import utility.
 * The import utility merges with the existing data. If data is removed from the
 * xml and imported back, that data will be removed if and only if there is 
 * no mapping for the reconciliation field.
 * 
 * Add "xlDDM.jar" is required to use tcImportOperationsIntf service 
 * "/home/oracle/Oracle/Middleware/Oracle_IDM1/designconsole/lib" 
 * directory to classpath in order to use.
 * 
 * Add xlDataObjects.jar to use prepared statement for tcDataProvider.
 * 
 * Note: Removing a reconciliation field that has a recon mapping through the xml will change
 * the recon mappings to empty value (E.g. UD_FLAT_FIL_ROLE =  ). This utility prevents
 * removal of recon fields that has a recon mapping defined.
 */
public class ReconFieldUtility 
{
    public static String RECON_FIELD_TAG = "ReconField"; //xml reconfield tag name
        
    //ReconField Attribute tags
    public static String ORF_UPDATE_TAG = "ORF_UPDATE";
    public static String ORF_FIELDTYPE_TAG = "ORF_FIELDTYPE";
    public static String ORF_REQUIRED_TAG = "ORF_REQUIRED";
    
    //Possible reconciliation field type
    public static String RECON_FIELD_TYPE_STRING = "String";
    public static String RECON_FIELD_TYPE_MULTI_VALUE = "Multi-Valued"; //Not supported for this utility
    public static String RECON_FIELD_TYPE_NUMBER = "Number";
    public static String RECON_FIELD_TYPE_IT_RESOURCE = "IT Resource";
    public static String RECON_FIELD_TYPE_DATE = "Date";
    
    //List of possible reconciliation field attribute names to be specified in file for add
    public static String RECON_FIELD_ATTR_NAME = "ReconFieldName"; //required
    public static String RECON_FIELD_ATTR_TYPE = "FieldType"; //required
    public static String RECON_FIELD_ATTR_ISREQUIRED = "isRequired";

    /*
     * Add reconciliation fields specified from a flat file.
     * Reconciliation field name is case sensitive.
     * Multivalued fields is not supported at the moment.
     * 
     * Checks:
     * Validate the recon field types are valid
     * Validate there are no recon field duplications
     * 
     * File Format
     * <recon field attribute names [ReconFieldName FieldType isRequired]>
     * <reconFieldRecord1>
     * <reconFieldRecord2>
     * 
     * @param   dbProvider          connection to the OIM Schema
     * @param   exportOps           tcExportOperationsIntf service object
     * @param   importOps           tcImportOperationsIntf service object
     * @param   fileName            file that contains the reconciliation fields to add
     * @param   resourceObjectName  Resource Object to add fields to
     * @param   delimiter           Use to separate values in file
     */
    public static Boolean addReconFieldsDSFF(tcDataProvider dbProvider, tcExportOperationsIntf exportOps, tcImportOperationsIntf importOps, String fileName, String resourceObjectName, String delimiter) throws tcDataSetException, tcDataAccessException, ResourceObjectNameNotFoundException, MissingRequiredFieldException, BadFileFormatException, FileNotFoundException, IOException, tcAPIException, ParserConfigurationException, SAXException, TransformerConfigurationException, TransformerException, SQLException, NamingException, DDMException, TransformationException, tcBulkException, XPathExpressionException, MissingHeaderException
    {            
        FileInputStream fstream = null;
        DataInputStream in = null;
        BufferedReader br = null;
        int lineNumber = 0;
            
        try 
        {    
            fstream = new FileInputStream(fileName); //Open File
            in = new DataInputStream(fstream); //Get the object of DataInputStream
            br = new BufferedReader(new InputStreamReader(in));
            
            String strLine; //var to store a line of a file
            ArrayList<String> reconFieldAttributeNameArray = new ArrayList<String>(); //store the name of the recon field attributes 
            ArrayList<ReconciliationField> newReconFieldArray = new ArrayList<ReconciliationField>(); //store all recon form fields to be added
            
            //Validate existence of resource object
            if(doesResourceObjectExist(dbProvider, resourceObjectName) == false)
            {
                System.out.println("[Error]: Resource Object name "+ resourceObjectName + " does not exist.");
                throw new ResourceObjectNameNotFoundException("Resource Object name "+ resourceObjectName + " does not exist.");
            }
            
            Long resourceObjectKey = getResourceObjectKey(dbProvider, resourceObjectName);
            System.out.println("Resource Object Key: " + resourceObjectKey);
                
            //First line contains the attributes of a reconciliation field
            //Each record in flat file must have values for these attributes
            String rf_AttributeNames = br.readLine();
            
            if(rf_AttributeNames == null)
            {
                throw new MissingHeaderException(String.format("Here are all the possible attribute names for file header: %s, %s, %s\n",
                     RECON_FIELD_ATTR_NAME, RECON_FIELD_ATTR_TYPE ,RECON_FIELD_ATTR_ISREQUIRED));
            }
            
            StringTokenizer attributeNameToken = new StringTokenizer(rf_AttributeNames, delimiter); 
            lineNumber++;
            
            while(attributeNameToken.hasMoreTokens())
            {
                String fieldAttributeName = attributeNameToken.nextToken(); 
                
                //Check if the name of the attribute is valid
                if(fieldAttributeName.equalsIgnoreCase(RECON_FIELD_ATTR_NAME))
                {
                    reconFieldAttributeNameArray.add(RECON_FIELD_ATTR_NAME);
                }
                
                else if(fieldAttributeName.equalsIgnoreCase(RECON_FIELD_ATTR_TYPE))
                {
                    reconFieldAttributeNameArray.add(RECON_FIELD_ATTR_TYPE);
                }
                
                else if(fieldAttributeName.equalsIgnoreCase(RECON_FIELD_ATTR_ISREQUIRED))
                {
                    reconFieldAttributeNameArray.add(RECON_FIELD_ATTR_ISREQUIRED);
                }
                
                else
                {
                    System.out.printf("Field attribute name %s is invalid.\n"
                    + "Here are all the possible attribute names: %s, %s, %s\n",
                    fieldAttributeName, RECON_FIELD_ATTR_NAME, RECON_FIELD_ATTR_TYPE 
                    ,RECON_FIELD_ATTR_ISREQUIRED);
                    throw new BadFileFormatException(String.format("Field attribute name %s is invalid.\n"
                    + "Here are all the possible attribute names: %s, %s, %s\n",
                    fieldAttributeName, RECON_FIELD_ATTR_NAME, RECON_FIELD_ATTR_TYPE 
                    ,RECON_FIELD_ATTR_ISREQUIRED));
                }
            }
           
            //Validate that the "ReconFieldName" attribute name is specified the file
            if(!reconFieldAttributeNameArray.contains(RECON_FIELD_ATTR_NAME))
            {
                System.out.println("'"+ RECON_FIELD_ATTR_NAME + "' is a required attribute to be specified in file");
                throw new MissingRequiredFieldException("'"+ RECON_FIELD_ATTR_NAME + "' is a required attribute to be specified in file");
            }
            
            //Validate that the "ReconFieldType" attribute name is specified the file
            if(!reconFieldAttributeNameArray.contains(RECON_FIELD_ATTR_TYPE))
            {
                System.out.println("'"+ RECON_FIELD_ATTR_TYPE + "' is a required attribute to be specified in file");
                throw new MissingRequiredFieldException("'"+ RECON_FIELD_ATTR_TYPE + "' is a required attribute to be specified in file");
            }
                
            HashMap<String,String> reconFieldDuplicationValidator = new HashMap<String,String>(); //validate if a recon field has already been added to staging
                    
            //Read each recon field from file
            while ((strLine = br.readLine()) != null)  
            {
                lineNumber++;
                String[] fieldAttributeValueToken = strLine.split(delimiter);
                int numFieldAttributeNames = reconFieldAttributeNameArray.size();
                int numTokens = fieldAttributeValueToken.length;
                ReconciliationField reconFieldObj = new ReconciliationField();
                
                if(numFieldAttributeNames != numTokens)
                {
                    System.out.println("[Warning] Line = " + lineNumber + " : Size of row is invalid. Field will not be added:\n" + strLine);
                    continue;
                }
                
                boolean isFieldRecordFromFileValid = true;
                
                for(int i = 0; i < numFieldAttributeNames; i++)
                {
                    String fieldAttributeName = reconFieldAttributeNameArray.get(i);
                    
                    if(fieldAttributeName.equalsIgnoreCase(RECON_FIELD_ATTR_NAME))
                    {
                        String fieldName = fieldAttributeValueToken[i];
                         
                        //Check if the recon field name exist
                        if(doesReconFieldNameExist(dbProvider, resourceObjectKey, fieldName) == true)
                        {
                            System.out.println("[Warning] Line = " + lineNumber + " : Recon Field '" + fieldName + "' exists. Field will not be added:\n" + strLine);
                            isFieldRecordFromFileValid = false;
                            break;
                        }
                        
                        //Check if reconciliation field has already been added to staging
                        if(reconFieldDuplicationValidator.containsKey(fieldName))
                        {
                            System.out.println("[Warning] Line = " + lineNumber + " : Recon Field '" + fieldName + "' exists in staging. Field will not be added:\n" + strLine);
                            isFieldRecordFromFileValid = false;
                            break;
                        }
                        
                        reconFieldDuplicationValidator.put(fieldName, null);
                        reconFieldObj.setReconFieldName(fieldName);
                    }

                    else if(fieldAttributeName.equalsIgnoreCase(RECON_FIELD_ATTR_TYPE))
                    {
                        String fieldType = fieldAttributeValueToken[i];
                        
                        if(fieldType.equalsIgnoreCase(RECON_FIELD_TYPE_MULTI_VALUE))
                        {
                            System.out.println("[Warning] Line = " + lineNumber + " : Field type '" + fieldType + "' is not supported. Field will not be added:\n" + strLine);
                            isFieldRecordFromFileValid = false;
                            break; 
                        }
                        
                         //check if the variant type is valid
                        if(!isReconFieldTypeValid(fieldType))
                        {
                            System.out.println("[Warning] Line = " + lineNumber + " : Field type '" + fieldType + "' is not valid. Field will not be added:\n" + strLine);
                            isFieldRecordFromFileValid = false;
                            break; 
                        }
                        
                        reconFieldObj.setReconFieldType(fieldType);
                    }

                    else if(fieldAttributeName.equalsIgnoreCase(RECON_FIELD_ATTR_ISREQUIRED))
                    {
                        String isRequiredStr = fieldAttributeValueToken[i];
                        boolean isRequired;
                        
                        //check if the field type is valid
                        if(isRequiredStr.equalsIgnoreCase("1"))
                        {
                            isRequired = true;
                        }
                        
                        else if(isRequiredStr.equalsIgnoreCase("0") || isRequiredStr.equalsIgnoreCase(""))
                        {
                            isRequired = false;
                        }
                            
                        else
                        {
                            System.out.println("[Warning] Line = " + lineNumber + " : Field type '" + isRequiredStr + "' is not valid. Field will not be added:\n" + strLine);
                            isFieldRecordFromFileValid = false;
                            break; 
                        }
                        
                        reconFieldObj.setIsRequired(isRequired);
                    }
                }
                
                //add form field object if field record in file is valid
                if(isFieldRecordFromFileValid)
                {
                    newReconFieldArray.add(reconFieldObj); 
                }
                
            }
            
            System.out.println(newReconFieldArray);
            
            //Do not perform export and import resource metadata if no recon fields are to be added
            if(newReconFieldArray.isEmpty())
            {
                System.out.println("No reconciliation fields to add.");
                return true;
            }
            
            String resourceObjectXML = ReconFieldUtility.exportResourceObject(exportOps, resourceObjectName); //Export the resource metadata as a String
            Document document = HelperUtility.parseStringXMLIntoDocument(resourceObjectXML); //convert xml to a Document
            
            //Add reconciliation fields to the resource metadata
            for(ReconciliationField newReconFieldToAdd: newReconFieldArray)
            { 
                addReconField(document, newReconFieldToAdd);
            }
            
            String newObjectResourceXML = HelperUtility.parseDocumentIntoStringXML(document);
            System.out.println(newObjectResourceXML);
            importResourceObject(importOps, newObjectResourceXML, "CustomReconFieldUtilAdd");
            return true;
        } 
        
        finally
        {
            if(br != null)
            {
                try {
                    br.close();
                } catch (IOException ex) {
                    Logger.getLogger(ProcessFormFieldUtility.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            
            if(in != null)
            {
                try {
                    in.close(); //Close the input stream
                } catch (IOException ex) {
                    Logger.getLogger(ProcessFormFieldUtility.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            
            if(fstream != null)
            {
                try {
                    fstream.close();
                } catch (IOException ex) {
                    Logger.getLogger(ProcessFormFieldUtility.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }      
    }
    
    /*
     * Remove reconciliation fields specified in a flat file.
     * This method does not remove mulitvalued attributes or single attributes
     * whose name is also a multivalued attribute name. For the latter case, 
     * you must manually delete through design console.
     * 
     * Checks:
     * Check existence of resource object
     * Validate existence of reconciliation field in OIM
     * Make sure there exist no mapping for the reconciliation field
     * 
     * File Format
     * <reconFieldName1>
     * <reconFieldName2>
     * 
     * @param   dbProvider          connection to the OIM Schema
     * @param   exportOps           tcExportOperationsIntf service object
     * @param   importOps           tcImportOperationsIntf service object
     * @param   fileName            file that contains the reconciliation fields to add
     * @param   resourceObjectName  Name of the resource object to remove recon fields from
     */
    public static Boolean removeReconFieldDSFF(tcDataProvider dbProvider, tcExportOperationsIntf exportOps, tcImportOperationsIntf importOps, String fileName, String resourceObjectName) throws tcDataSetException, tcDataAccessException, ResourceObjectNameNotFoundException, FileNotFoundException, IOException, tcAPIException, ParserConfigurationException, SAXException, TransformerConfigurationException, TransformerException, SQLException, NamingException, DDMException, TransformationException, tcBulkException, XPathExpressionException
    {     
        FileInputStream fstream = null;
        DataInputStream in = null;
        BufferedReader br = null;
        int lineNumber = 0;
            
        try 
        {    
            fstream = new FileInputStream(fileName); //Open File
            in = new DataInputStream(fstream); //Get the object of DataInputStream
            br = new BufferedReader(new InputStreamReader(in));
            String strLine; //var to store a line of a file
           
            //Validate the existence of resource object
            if(doesResourceObjectExist(dbProvider, resourceObjectName) == false)
            {
                System.out.println("[Error]: Resource Object name "+ resourceObjectName + " does not exist.");
                throw new ResourceObjectNameNotFoundException("Resource Object name "+ resourceObjectName + " does not exist.");
            }
            
            Long resourceObjectKey = getResourceObjectKey(dbProvider, resourceObjectName);
            System.out.println("Resource Object Key = " + resourceObjectKey);
            HashMap<String,String> reconFieldToRemove = new HashMap<String,String>(); //store the recon field names to be removed
                    
            //Read each recon field name from file
            while ((strLine = br.readLine()) != null)  
            {             
                lineNumber++;
                String fieldName = strLine;
      
                //Check if the recon field name exist
                if(doesReconFieldNameExist(dbProvider, resourceObjectKey, fieldName) == false)
                {      
                    System.out.println("[Warning] Line = " + lineNumber + " : Recon Field '" + fieldName + "' does not exists.");
                    continue;
                }
                
                //Check if the recon field is a multivalued attribute
                if(isReconFieldMulitvalued(dbProvider, resourceObjectKey, fieldName) == true)
                {
                    System.out.println("[Warning] Line = " + lineNumber + " : Recon Field'" + fieldName + "' is a mulitvalued attribute.");
                    continue;
                }
                
                //Check if the recon field is a child attribute
                if(isReconFieldChildAttribute(dbProvider, resourceObjectKey , fieldName) == true)
                {
                    System.out.println("[Warning] Line = " + lineNumber + " : Recon Field'" + fieldName + "' is a child attribute.");
                    continue;   
                }
                  
                //Check if reconciliation field has already been added to staging
                if(reconFieldToRemove.containsKey(fieldName))
                {      
                    System.out.println("[Warning] Line = " + lineNumber + " : Recon Field '" + fieldName + "' exists in staging.");
                    continue;
                }
                
                String reconFieldKey = MappingReconFieldToFormFieldUtility.getReconFieldKey(dbProvider, resourceObjectKey, fieldName);
             
                //Validate if a recon field has a mapping
                if(MappingReconFieldToFormFieldUtility.isReconFieldMapped(dbProvider, reconFieldKey) == true)
                {   
                     System.out.println("[Warning]: Line = " + lineNumber + " Reconciliation field '"+ fieldName + "'cannot be removed until mapping is removed");
                     continue; 
                }
                
                reconFieldToRemove.put(fieldName, null);  
            }

            System.out.println(reconFieldToRemove);
                    
            //Do not perform export and import resource metadata if no recon fields are to be deleted
            if(reconFieldToRemove.isEmpty())
            {
                System.out.println("No reconciliation fields to delete.");
                return true;
            }
            
            String resourceObjectXML = ReconFieldUtility.exportResourceObject(exportOps, resourceObjectName); //Export the resource metadata as a String
            Document document = HelperUtility.parseStringXMLIntoDocument(resourceObjectXML); //convert xml to a Document
            
            //Remove reconciliation fields to the resource metadata
            for(Map.Entry<String,String> entry: reconFieldToRemove.entrySet())
            {
                String reconFieldName = entry.getKey();  
                removeReconField(document,reconFieldName);
            }
            
            String newObjectResourceXML = HelperUtility.parseDocumentIntoStringXML(document);
            System.out.println(newObjectResourceXML);
            importResourceObject(importOps, newObjectResourceXML, "CustomReconFieldUtilRemove");
            return true;
        } 
        
        finally
        {
            if(br != null)
            {
                try {
                    br.close();
                } catch (IOException ex) {
                    Logger.getLogger(ProcessFormFieldUtility.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            
            if(in != null)
            {
                try {
                    in.close(); //Close the input stream
                } catch (IOException ex) {
                    Logger.getLogger(ProcessFormFieldUtility.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            
            if(fstream != null)
            {
                try {
                    fstream.close();
                } catch (IOException ex) {
                    Logger.getLogger(ProcessFormFieldUtility.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }      
    }
    
    /*
     * Adds a reconciliation field to the resource xml.
     * Sample data added to xml
     * <ReconField repo-type="RDBMS" name="test3">
     * <ORF_UPDATE>1361032854000</ORF_UPDATE>
     * <ORF_FIELDTYPE>String</ORF_FIELDTYPE>
     * <ORF_REQUIRED>0</ORF_REQUIRED>
     * </ReconField>
     * 
     * Set the ORF_UPDATE to the date of when the object was last updated (OBJ_UPDATE).
     * @param document              object representation of an object resource xml
     * @param newReconFieldToAdd    reconciliation field to add to document
     */
    public static void addReconField(Document document, ReconciliationField newReconFieldToAdd) throws XPathExpressionException
    {          
        String reconFieldName = newReconFieldToAdd.getReconFieldName();
        String reconFieldType = newReconFieldToAdd.getReconFieldType();
        Boolean isRequired = newReconFieldToAdd.getIsRequired();
        String reconFieldUpdateTimestamp = getResourceObjectUpdateTimestamp(document);
                
        //Locate proper level to add the new recon field into the xml
        XPathFactory xPathFactory = XPathFactory.newInstance();
        XPath xpath = xPathFactory.newXPath();
        NodeList nodes =  (NodeList) xpath.evaluate("xl-ddm-data/Resource", document, XPathConstants.NODESET);
            
        //ReconField tag and its properties
        Element newReconField = document.createElement(RECON_FIELD_TAG);
        Element rfAttrUpdate = document.createElement(ORF_UPDATE_TAG);
        Element rfAttrFieldType = document.createElement(ORF_FIELDTYPE_TAG);
        Element rfAttrIsRequired = document.createElement(ORF_REQUIRED_TAG);
            
        //Set ReconField tag attributes and properties
        newReconField.setAttribute("repo-type", "RDBMS"); 
        newReconField.setAttribute("name", reconFieldName); 
        rfAttrUpdate.setTextContent(reconFieldUpdateTimestamp);
        rfAttrFieldType.setTextContent(reconFieldType);
        String isRequiredString = isRequired ? "1" : "0";
        rfAttrIsRequired.setTextContent(isRequiredString);
        
        //Append properties to the ReconField tag
        newReconField.appendChild(rfAttrUpdate);
        newReconField.appendChild(rfAttrFieldType);
        newReconField.appendChild(rfAttrIsRequired);
        
        //Get the resource node tag and insert reconField within the resource tag
        Node resourceNode = nodes.item(0); 
        resourceNode.appendChild(newReconField); 
    }
    
    /*
     * Removes a reconciliation field from the resource xml.
     * The reconciliation field can only be removed if and only if the
     * reconciliation field has not been mapped to a form field.
     * @param   document           object representation of an object resource xml
     * @param   reconFieldName     reconciliation field to remove from document
     */
    public static void removeReconField(Document document, String reconFieldName) throws XPathExpressionException
    {  
        XPathFactory xpf = XPathFactory.newInstance();
        XPath xpath = xpf.newXPath();
        XPathExpression expression = xpath.compile("xl-ddm-data/Resource/ReconField[@name=\""+ reconFieldName +"\"]");
            
        Node reconFieldNode = (Node) expression.evaluate(document, XPathConstants.NODE); //pinpoint recon field to remove
        reconFieldNode.getParentNode().removeChild(reconFieldNode); //Get the parent node then remove target child node        
    }
    
    /*
     * Export a resource object XML
     * @param   exportOps           tcExportOperationsIntf service object
     * @param   resourceObjectName  name of resource object to export
     * @return  the XML of the resource as a String
     */
    public static String exportResourceObject(tcExportOperationsIntf exportOps, String resourceObjectName) throws tcAPIException
    {
         String type = "Resource";
         String description = null;
         Collection resourceObject = exportOps.findObjects(type, resourceObjectName);
         int numObjects = resourceObject.size();
         
         //enforce one resource object to be exported at a time
         if(numObjects == 1)
         {
             String resourceObjectXML = exportOps.getExportXML(resourceObject, description);
             return resourceObjectXML;
         }
         
         System.out.println("Only one object can be exported at a time.");
         return null;
    }
    
    /*
     * Import resource object XML into OIM
     * @param   importOps               tcImportOperationsIntf service object
     * @param   newObjectResourceXML    xml content to be imported
     * @param   fileName                File name of the file being imported. For tracking purposes.
     * @return  the XML of the resource as a String
     */
    public static void importResourceObject(tcImportOperationsIntf importOps, String newObjectResourceXML, String fileName) throws SQLException, NamingException, DDMException, tcAPIException, TransformationException, tcBulkException
    {     
        importOps.acquireLock(true);
        Collection<RootObject> justImported = importOps.addXMLFile(fileName, newObjectResourceXML);
        importOps.performImport(justImported);
    }
    
    /*
     * Get the update timestamp of a resource object.
     * @param   document    object representation of an object resource xml
     * @return   timestmap given in the resource object xml
     */
    public static String getResourceObjectUpdateTimestamp(Document document) throws XPathExpressionException
    {         
        XPathFactory xPathFactory = XPathFactory.newInstance();
        XPath xpath = xPathFactory.newXPath();
        XPathExpression expr = xpath.compile("//OBJ_UPDATE"); //Get all tags with "ReconField" tag name regardless of depth
        NodeList nodes = (NodeList) expr.evaluate(document, XPathConstants.NODESET);
        int numReconFieldNodes = nodes.getLength();
 
        for(int i = 0; i < numReconFieldNodes; i++)
        {
            Node node = nodes.item(i);
            String textContent = node.getTextContent();
            
            if(textContent != null || !textContent.isEmpty())
            {
                return textContent;
            }
        }
        
        return null;
    }
    
    /*
     * Get resource object name 
     * @param   dbProvider           connection to the OIM Schema 
     * @param   resourceObjectKey    resource object key (OBJ.OBJ_KEY)  
     * @return  corresponding resource object name
     */
    public static String getResourceObjectName(tcDataProvider dbProvider, Long resourceObjectKey) throws tcDataSetException, tcDataAccessException
    {
        tcDataSet objDataSet = null;
        PreparedStatementUtil ps = null;
  
        try 
        {
            String query = "SELECT OBJ_NAME FROM OBJ WHERE OBJ_KEY = ?";
            ps = new PreparedStatementUtil();
            ps.setStatement(dbProvider, query);
            ps.setLong(1, resourceObjectKey);
            ps.execute();
            objDataSet = ps.getDataSet();
            String resourceObjectName  = objDataSet.getString("OBJ_NAME"); ;
            return resourceObjectName;
        } 
        
        finally
        {
        }   
    }
    
    /*
     * Get resource object key
     * @param   dbProvider          connection to the OIM Schema 
     * @param   resourceObjectName  resource object key (OBJ.OBJ_NAME)  
     * @return  corresponding resource object name
     */
    public static Long getResourceObjectKey(tcDataProvider dbProvider, String resourceObjectName) throws tcDataSetException, tcDataAccessException, ResourceObjectNameNotFoundException
    {    
        tcDataSet resourceDataSet = null;
        PreparedStatementUtil ps = null;

        try 
        {
            String query = "SELECT OBJ_KEY FROM OBJ WHERE LOWER(OBJ_NAME) = LOWER(?)";
            ps = new PreparedStatementUtil();
            ps.setStatement(dbProvider, query);
            ps.setString(1, resourceObjectName);
            ps.execute();
            resourceDataSet = ps.getDataSet();
            Long resourceObjectKey = resourceDataSet.getLong("OBJ_KEY");
            
            if(resourceObjectKey == 0)
            {
                throw new ResourceObjectNameNotFoundException(String.format("Resource '%s' does not exist", resourceObjectName));
            }
             
            return resourceObjectKey;
        } 
        
        finally
        {  
        }   
    }
    
    /* 
     * Determine if a resource object exists.
     * @param   dbProvider             connection to the OIM Schema 
     * @param   resourceObjectName     resource object name (OBJ.OBJ_NAME)  
     * @return  true if resource object exists; false otherwise
     */    
    public static Boolean doesResourceObjectExist(tcDataProvider dbProvider, String resourceObjectName) throws tcDataSetException, tcDataAccessException
    {
        tcDataSet resourceDataSet = null;
        PreparedStatementUtil ps = null;

        try 
        {
            String query = "SELECT COUNT(*) AS numRows FROM OBJ WHERE LOWER(OBJ_NAME) = LOWER(?)";
            ps = new PreparedStatementUtil();
            ps.setStatement(dbProvider, query);
            ps.setString(1, resourceObjectName);
            ps.execute();
            resourceDataSet = ps.getDataSet();
            int numRecords = resourceDataSet.getInt("numRows");
            
            if(numRecords == 1)
            {
               return true;  
            }    
        }
        
        finally
        {
        }

        return false;
    }
        
    /*
     * Determine if the reconciliation field name exists.
     * This check includes child recon fields. OIM allows recon
     * fields to have the same name if they are in different levels 
     * (E.g. field on parent level and field on child level).
     * Reconciliation fields are case sensitive.
     * @param   dbProvider          connection to the OIM Schema 
     * @param   resourceObjectKey   resource object (OBJ.OBJ_KEY)
     * @param   reconFieldName      recon field name to check (ORF_FIELD_NAME) 
     * @return  true if the recon field name exists; false otherwise
     */
    public static Boolean doesReconFieldNameExist(tcDataProvider dbProvider, Long resourceObjectKey ,String reconFieldName) throws tcDataSetException, tcDataAccessException
    {    
        tcDataSet rfDataSet = null;
        PreparedStatementUtil ps = null;
        
        try 
        {
            String query = "SELECT COUNT(*) AS numRows FROM ORF WHERE "
                    + "OBJ_KEY = ? AND ORF_FIELDNAME = ?";
            
            ps = new PreparedStatementUtil();
            ps.setStatement(dbProvider, query);
            ps.setLong(1, resourceObjectKey);
            ps.setString(2, reconFieldName);
            ps.execute();
            rfDataSet = ps.getDataSet();
            int numRecords = rfDataSet.getInt("numRows");
            
            if(numRecords >= 1)
            {
               return true;  
            }    
        } 
        
        finally
        { 
        }   
        
        return false;
    }
    
    /*
     * Determine if the field type of a reconciliation field is valid.
     * @param   fieldType   name of field type
     * @return  boolean value to indicate if a variant type is valid
     */
    public static boolean isReconFieldTypeValid(String fieldType)
    {
        return fieldType.equalsIgnoreCase(RECON_FIELD_TYPE_STRING) || fieldType.equalsIgnoreCase(RECON_FIELD_TYPE_MULTI_VALUE)  
        || fieldType.equalsIgnoreCase(RECON_FIELD_TYPE_NUMBER) || fieldType.equalsIgnoreCase(RECON_FIELD_TYPE_IT_RESOURCE) 
        || fieldType.equalsIgnoreCase(RECON_FIELD_TYPE_DATE);
    }
    
    /*
     * Print all the Resource Objects in OIM. Queries from the OBJ table.
     * @param dbProvider connection to the OIM Schema 
     */
    public static void printAllResourceObjects(tcDataProvider dbProvider) throws tcDataSetException, tcDataAccessException 
    {
        tcDataSet objDataSet = null;
        PreparedStatementUtil ps = null;
            
        try 
        {
            String query = "SELECT OBJ_KEY, OBJ_TYPE, OBJ_NAME FROM OBJ ORDER BY OBJ_NAME";
            ps = new PreparedStatementUtil();
            ps.setStatement(dbProvider, query);
            ps.execute();
            objDataSet = ps.getDataSet();
            System.out.println(objDataSet.toString());
        } 
        
        finally
        {
        }
    }
    
    /*
     * Print all the reconciliation fields in a resource object.
     * Table ORF - contains all the reconciliation fields  
     * @param   dbProvider  connection to the OIM Schema 
     * @param   objectKey   resource object key (ORF.OBJ_KEY)    
     */
    public static void printReconFieldsofResourceObject(tcDataProvider dbProvider, long objectKey) throws tcDataSetException, tcDataAccessException 
    {
        tcDataSet orfDataSet = null;
        PreparedStatementUtil ps = null;
            
        try 
        {
            System.out.printf("%-25s%-25s%-25s\n", "ReconFieldKey", "ReconFieldName", "FieldType");
            String query = "SELECT ORF_KEY, ORF_FIELDNAME, ORF_FIELDTYPE FROM ORF WHERE OBJ_KEY = ? ORDER BY ORF_FIELDNAME";      
            ps = new PreparedStatementUtil();
            ps.setStatement(dbProvider, query);
            ps.setLong(1, objectKey);
            ps.execute();
            orfDataSet = ps.getDataSet();
            System.out.println(orfDataSet.toString());
        }
        
        finally
        {
        }
    }
    
     /*
     * Print all the reconciliation fields in a resource object
     * excluding mulitvalued fields and their child components.
     * Table ORF - contains all the reconciliation fields  
     * @param    dbProvider     connection to the OIM Schema 
     * @param    fileName       File to write to
     * @param    objectKey      resource object key (ORF.OBJ_KEY)  
     * @param    fileDelimiter  Use to separate values in file
     */
    public static void exportReconFieldsofResourceObjectFileFormatAdd(tcDataProvider dbProvider, String fileName ,String resourceObjectName, String delimiter) throws tcDataSetException, tcDataAccessException, ResourceObjectNameNotFoundException, FileNotFoundException, UnsupportedEncodingException 
    {  
        PrintWriter writer = null;
        tcDataSet orfDataSet = null;
        PreparedStatementUtil ps = null;
            
        try 
        {
            writer = new PrintWriter(fileName, "UTF-8");
                        
            //Validate existence of resource object
            if(doesResourceObjectExist(dbProvider, resourceObjectName) == false)
            {
                System.out.println("[Error]: Resource Object name "+ resourceObjectName + " does not exist.");
                throw new ResourceObjectNameNotFoundException("Resource Object name "+ resourceObjectName + " does not exist.");
            }
            
            Long objectKey = getResourceObjectKey(dbProvider, resourceObjectName);
            
            String query = "SELECT ORF_FIELDNAME, ORF_FIELDTYPE, ORF_REQUIRED FROM "
                    + "ORF WHERE OBJ_KEY = ? AND ORF_PARENT_ORF_KEY IS NULL AND "
                    + "ORF_FIELDTYPE != 'Multi-Valued' ORDER BY ORF_FIELDNAME";
            
            ps = new PreparedStatementUtil();
            ps.setStatement(dbProvider, query);
            ps.setLong(1, objectKey);
            ps.execute();
            orfDataSet = ps.getDataSet();
            int numRecords = orfDataSet.getTotalRowCount();
            writer.printf("%s%s%s%s%s\n", "ReconFieldName", delimiter, "FieldType", delimiter, "isRequired");
           
            for(int i = 0; i < numRecords; i++)
            {
                orfDataSet.goToRow(i);
                String reconFieldName = orfDataSet.getString("ORF_FIELDNAME"); 
                String reconFieldType = orfDataSet.getString("ORF_FIELDTYPE");
                String isRequired = orfDataSet.getString("ORF_REQUIRED");
                writer.printf("%s%s%s%s%s\n", reconFieldName, delimiter, reconFieldType, delimiter, isRequired);
            }
        }
      
        finally
        {
            if(writer != null)
            {
                writer.close();
            }
        }
    }
    
    /*
     * Determines if a reconciliation field is multivalued.
     * @param   dbProvider          connection to the OIM Schema 
     * @param   resourceObjectKey   resource object
     * @param   reconFieldName      recon field name to check
     * @return  boolean value; true if recon field is mulitvalued; false otherwise
     */
    public static Boolean isReconFieldMulitvalued(tcDataProvider dbProvider, Long resourceObjectKey ,String reconFieldName) throws tcDataSetException, tcDataAccessException
    {   
        tcDataSet rfDataSet = null;
        PreparedStatementUtil ps = null;

        try 
        {
            String query = "SELECT ORF_FIELDTYPE FROM ORF WHERE "
                    + "OBJ_KEY = ? AND ORF_FIELDNAME = ?";
            
            ps = new PreparedStatementUtil();
            ps.setStatement(dbProvider, query);
            ps.setLong(1, resourceObjectKey);
            ps.setString(2, reconFieldName);
            ps.execute();
            rfDataSet = ps.getDataSet();
            
            String type = rfDataSet.getString("ORF_FIELDTYPE");
            if(type.equalsIgnoreCase(RECON_FIELD_TYPE_MULTI_VALUE))
            {
                return true;
            }  
        } 
        
        finally
        { 
        }   
        
        return false;
    }
    
    /*
     * Determines if a reconciliation field is a child attribute.
     * This checks if the name of recon field is listed as a child attribute. 
     * @param   dbProvider          connection to the OIM Schema 
     * @param   resourceObjectKey   resource object
     * @param   reconFieldName      recon field name to check
     * @return  boolean value; true is recon field within a mulitvalued group field, false otherwise 
     */
    public static Boolean isReconFieldChildAttribute(tcDataProvider dbProvider, Long resourceObjectKey, String reconFieldName) throws tcDataSetException, tcDataAccessException
    {    
        tcDataSet rfDataSet = null;
        PreparedStatementUtil ps = null;

        try 
        {
            String query = "SELECT ORF_KEY, ORF_PARENT_ORF_KEY FROM ORF WHERE "
                    + "OBJ_KEY = ? AND ORF_PARENT_ORF_KEY IS NOT NULL AND ORF_FIELDNAME = ?";
                
            ps = new PreparedStatementUtil();
            ps.setStatement(dbProvider, query);
            ps.setLong(1, resourceObjectKey);
            ps.setString(2, reconFieldName);
            ps.execute();
            rfDataSet = ps.getDataSet();
               
            String isChildAttribute = rfDataSet.getString("ORF_KEY");
            if(isChildAttribute != null && !isChildAttribute.isEmpty())
            { 
                return true;
            }
        } 
        
        finally
        { 
        }   
        
        return false;
    } 
}