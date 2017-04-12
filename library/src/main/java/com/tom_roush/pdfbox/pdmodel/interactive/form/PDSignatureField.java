package com.tom_roush.pdfbox.pdmodel.interactive.form;

import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSeedValue;
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * A signature field is a form field that contains a digital signature.
 *
 * @author Ben Litchfield
 * @author Thomas Chojecki
 */
public class PDSignatureField extends PDTerminalField
{
    /**
     * @see PDTerminalField#PDTerminalField(PDAcroForm)
     *
     * @param acroForm The acroForm for this field.
     * @throws IOException If there is an error while resolving partial name for the signature field
     * or getting the widget object.
     */
    public PDSignatureField(PDAcroForm acroForm) throws IOException
    {
        super(acroForm);
        dictionary.setItem(COSName.FT, COSName.SIG);
        getWidgets().get(0).setLocked(true);
        getWidgets().get(0).setPrinted(true);
        setPartialName(generatePartialName());
    }

    /**
     * Constructor.
     *
     * @param acroForm The form that this field is part of.
     * @param field the PDF object to represent as a field.
     * @param parent the parent node of the node to be created
     */
    PDSignatureField(PDAcroForm acroForm, COSDictionary field, PDNonTerminalField parent)
    {
        super(acroForm, field, parent);
    }

    /**
     * Generate a unique name for the signature.
     *
     * @return the signature's unique name
     */
    private String generatePartialName()
    {
        String fieldName = "Signature";
        Set<String> sigNames = new HashSet<String>();
        // fixme: this ignores non-terminal fields, so will miss any descendant signatures
        for (PDField field : acroForm.getFields())
        {
            if (field instanceof PDSignatureField)
            {
                sigNames.add(field.getPartialName());
            }
        }
        int i = 1;
        while (sigNames.contains(fieldName + i))
        {
            ++i;
        }
        return fieldName + i;
    }

    /**
     * Add a signature dictionary to the signature field.
     *
     * @param value is the PDSignatureField
     */
    public void setSignature(PDSignature value)
    {
        setValue(value);
    }

    /**
     * Get the signature dictionary.
     *
     * @return the signature dictionary
     */
    public PDSignature getSignature()
    {
        return getValue();
    }

    /**
     * Add a signature dictionary to the signature field.
     *
     * @param value is the PDSignatureField
     */
    public void setValue(PDSignature value)
    {
        if (value == null)
        {
            dictionary.removeItem(COSName.V);
        }
        else
        {
            dictionary.setItem(COSName.V, value);
        }
    }

    @Override
    public void setValue(String fieldValue)
    {
        // Signature fields don't support the strings for value
        throw new IllegalArgumentException("Signature fields don't support a string for the value entry.");
    }

    @Override
    public PDSignature getValue()
    {
        COSBase value = dictionary.getDictionaryObject(COSName.V);
        if (value == null)
        {
            return null;
        }
        return new PDSignature((COSDictionary) value);
    }

    /**
     * <p>(Optional; PDF 1.5) A seed value dictionary containing information
     * that constrains the properties of a signature that is applied to the
     * field.</p>
     *
     * @return the seed value dictionary as PDSeedValue
     */
    public PDSeedValue getSeedValue()
    {
        COSDictionary dict = (COSDictionary) dictionary.getDictionaryObject(COSName.SV);
        PDSeedValue sv = null;
        if (dict != null)
        {
            sv = new PDSeedValue(dict);
        }
        return sv;
    }

    /**
     * <p>(Optional; PDF 1.) A seed value dictionary containing information
     * that constrains the properties of a signature that is applied to the
     * field.</p>
     *
     * @param sv is the seed value dictionary as PDSeedValue
     */
    public void setSeedValue(PDSeedValue sv)
    {
        if (sv != null)
        {
            dictionary.setItem(COSName.SV, sv);
        }
    }

    @Override
    public Object getDefaultValue()
    {
        // Signature fields don't support the "DV" entry.
        return null;
    }

    @Override
    public void setDefaultValue(String value)
    {
        // Signature fields don't support the "DV" entry.
        throw new IllegalArgumentException("Signature fields don't support the \"DV\" entry.");
    }

    @Override
    public String toString()
    {
        return "PDSignatureField";
    }

    @Override
    void constructAppearances() throws IOException
    {
        // TODO: implement appearance generation for signatures
        throw new UnsupportedOperationException("not implemented");
    }
}