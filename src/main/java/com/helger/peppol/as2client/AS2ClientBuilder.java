/**
 * Copyright (C) 2014-2015 Philip Helger (www.helger.com)
 * philip[at]helger[dot]com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.helger.peppol.as2client;

import java.io.File;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.OverridingMethodsMustInvokeSuper;
import javax.annotation.concurrent.NotThreadSafe;
import javax.xml.transform.stream.StreamResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unece.cefact.namespaces.sbdh.StandardBusinessDocument;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import com.helger.as2lib.client.AS2Client;
import com.helger.as2lib.client.AS2ClientRequest;
import com.helger.as2lib.client.AS2ClientResponse;
import com.helger.as2lib.client.AS2ClientSettings;
import com.helger.as2lib.crypto.ECryptoAlgorithmSign;
import com.helger.as2lib.disposition.DispositionOptions;
import com.helger.commons.ValueEnforcer;
import com.helger.commons.email.EmailAddressHelper;
import com.helger.commons.io.resource.FileSystemResource;
import com.helger.commons.io.resource.IReadableResource;
import com.helger.commons.io.resource.inmemory.ReadableResourceByteArray;
import com.helger.commons.io.stream.NonBlockingByteArrayOutputStream;
import com.helger.commons.string.StringHelper;
import com.helger.commons.url.URLHelper;
import com.helger.commons.xml.serialize.read.DOMReader;
import com.helger.peppol.identifier.IDocumentTypeIdentifier;
import com.helger.peppol.identifier.IParticipantIdentifier;
import com.helger.peppol.identifier.IProcessIdentifier;
import com.helger.peppol.identifier.IdentifierHelper;
import com.helger.peppol.sbdh.PeppolSBDHDocument;
import com.helger.peppol.sbdh.write.PeppolSBDHDocumentWriter;
import com.helger.peppol.smp.ESMPTransportProfile;
import com.helger.peppol.smp.EndpointType;
import com.helger.peppol.smpclient.SMPClientReadOnly;
import com.helger.peppol.smpclient.exception.SMPClientException;
import com.helger.sbdh.SBDMarshaller;

/**
 * A builder class for easy usage of the AS2 client for sending messages to a
 * PEPPOL participant. After building use the {@link #sendSynchronous()} message
 * to trigger the sending. All parameters that not explicitly have a default
 * value must be set otherwise the verification process will fail.
 *
 * @author Philip Helger
 */
@NotThreadSafe
public class AS2ClientBuilder
{
  public static final String DEFAULT_AS2_SUBJECT = "OpenPEPPOL AS2 message";
  public static final ECryptoAlgorithmSign DEFAULT_SIGNING_ALGORITHM = ECryptoAlgorithmSign.DIGEST_SHA1;
  public static final String DEFAULT_AS2_MESSAGE_ID_FORMAT = "OpenPEPPOL-$date.ddMMyyyyHHmmssZ$-$rand.1234$@$msg.sender.as2_id$_$msg.receiver.as2_id$";

  private static final Logger s_aLogger = LoggerFactory.getLogger (AS2ClientBuilder.class);

  private IAS2ClientBuilderMessageHandler m_aMessageHandler = new DefaultAS2ClientBuilderMessageHandler ();
  private File m_aKeyStoreFile;
  private String m_sKeyStorePassword;
  private String m_sAS2Subject = DEFAULT_AS2_SUBJECT;
  private String m_sSenderAS2ID;
  private String m_sSenderAS2Email;
  private String m_sSenderAS2KeyAlias;
  private String m_sReceiverAS2ID;
  private String m_sReceiverAS2KeyAlias;
  private String m_sReceiverAS2Url;
  private X509Certificate m_aReceiverCert;
  private ECryptoAlgorithmSign m_eSigningAlgo = DEFAULT_SIGNING_ALGORITHM;
  private String m_sMessageIDFormat = DEFAULT_AS2_MESSAGE_ID_FORMAT;
  private IReadableResource m_aBusinessDocument;
  private IParticipantIdentifier m_aPeppolSenderID;
  private IParticipantIdentifier m_aPeppolReceiverID;
  private IDocumentTypeIdentifier m_aPeppolDocumentTypeID;
  private IProcessIdentifier m_aPeppolProcessID;
  private SMPClientReadOnly m_aSMPClient;

  /**
   * Default constructor.
   */
  public AS2ClientBuilder ()
  {}

  /**
   * @return The internal message handler. Only required for derived classes
   *         that want to add additional verification mechanisms.
   */
  @Nonnull
  protected final IAS2ClientBuilderMessageHandler getMessageHandler ()
  {
    return m_aMessageHandler;
  }

  /**
   * Set the message handler to be used by the {@link #verifyContent()} method.
   * By default an instance of {@link DefaultAS2ClientBuilderMessageHandler} is
   * used so this method should only be called if you have special auditing
   * requirements.
   *
   * @param aMessageHandler
   *        The message handler to be used. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setMessageHandler (@Nonnull final IAS2ClientBuilderMessageHandler aMessageHandler)
  {
    m_aMessageHandler = ValueEnforcer.notNull (aMessageHandler, "MessageHandler");
    return this;
  }

  /**
   * Set the key store file and password for the AS2 client. The key store must
   * be an existing file of type PKCS12 containing at least the key alias of the
   * sender (see {@link #setSenderAS2ID(String)}). The key store file must be
   * writable as dynamically certificates of partners are added.
   *
   * @param aKeyStoreFile
   *        The existing key store file. Must exist and may not be
   *        <code>null</code>.
   * @param sKeyStorePassword
   *        The password to the key store. May not be <code>null</code> but
   *        empty.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setPKCS12KeyStore (@Nullable final File aKeyStoreFile,
                                             @Nullable final String sKeyStorePassword)
  {
    m_aKeyStoreFile = aKeyStoreFile;
    m_sKeyStorePassword = sKeyStorePassword;
    return this;
  }

  /**
   * Set the subject for the AS2 message. By default
   * {@value #DEFAULT_AS2_SUBJECT} is used so you don't need to set it.
   *
   * @param sAS2Subject
   *        The new AS2 subject. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setAS2Subject (@Nullable final String sAS2Subject)
  {
    m_sAS2Subject = sAS2Subject;
    return this;
  }

  /**
   * Set the AS2 sender ID (your ID). It is mapped to the "AS2-From" header. For
   * PEPPOL the AS2 sender ID must be the common name (CN) of the sender's AP
   * certificate subject. Therefore it usually starts with "APP_".
   *
   * @param sSenderAS2ID
   *        The AS2 sender ID to be used. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setSenderAS2ID (@Nullable final String sSenderAS2ID)
  {
    m_sSenderAS2ID = sSenderAS2ID;
    return this;
  }

  /**
   * Set the email address of the sender. This is required for the AS2 protocol
   * but not (to my knowledge) used in PEPPOL.
   *
   * @param sSenderAS2Email
   *        The email address of the sender. May not be <code>null</code> and
   *        must be a valid email address.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setSenderAS2Email (@Nullable final String sSenderAS2Email)
  {
    m_sSenderAS2Email = sSenderAS2Email;
    return this;
  }

  /**
   * Set the key alias of the sender's key in the key store (see
   * {@link #setPKCS12KeyStore(File, String)}). For PEPPOL the key alias of the
   * sender should be identical to the AS2 sender ID (
   * {@link #setSenderAS2ID(String)}), so it should also start with "APP_" (I
   * think case insensitive for PKCS12 key stores).
   *
   * @param sSenderAS2KeyAlias
   *        The sender key alias to be used. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setSenderAS2KeyAlias (@Nullable final String sSenderAS2KeyAlias)
  {
    m_sSenderAS2KeyAlias = sSenderAS2KeyAlias;
    return this;
  }

  /**
   * Set the AS2 receiver ID (recipient ID). It is mapped to the "AS2-To"
   * header. For PEPPOL the AS2 receiver ID must be the common name (CN) of the
   * receiver's AP certificate subject (as determined by the SMP query).
   * Therefore it usually starts with "APP_".
   *
   * @param sReceiverAS2ID
   *        The AS2 receiver ID to be used. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setReceiverAS2ID (@Nullable final String sReceiverAS2ID)
  {
    m_sReceiverAS2ID = sReceiverAS2ID;
    return this;
  }

  /**
   * Set the key alias of the receiver's key in the key store (see
   * {@link #setPKCS12KeyStore(File, String)}). For PEPPOL the key alias of the
   * receiver should be identical to the AS2 receiver ID (
   * {@link #setReceiverAS2ID(String)}), so it should also start with "APP_" (I
   * think case insensitive for PKCS12 key stores).
   *
   * @param sReceiverAS2KeyAlias
   *        The receiver key alias to be used. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setReceiverAS2KeyAlias (@Nullable final String sReceiverAS2KeyAlias)
  {
    m_sReceiverAS2KeyAlias = sReceiverAS2KeyAlias;
    return this;
  }

  /**
   * Set the AS2 endpoint URL of the receiver. This URL should be determined by
   * an SMP query.
   *
   * @param sReceiverAS2Url
   *        The AS2 endpoint URL of the receiver. This must be a valid URL. May
   *        not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setReceiverAS2Url (@Nullable final String sReceiverAS2Url)
  {
    m_sReceiverAS2Url = sReceiverAS2Url;
    return this;
  }

  /**
   * Set the public certificate of the receiver as determined by the SMP query.
   *
   * @param aReceiverCert
   *        The receiver certificate. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setReceiverCertificate (@Nullable final X509Certificate aReceiverCert)
  {
    m_aReceiverCert = aReceiverCert;
    return this;
  }

  /**
   * Set the algorithm to be used to sign AS2 messages. By default
   * {@link #DEFAULT_SIGNING_ALGORITHM} is used. An encryption algorithm cannot
   * be set because according to the PEPPOL AS2 specification the AS2 messages
   * may not be encrypted on a business level.
   *
   * @param eSigningAlgo
   *        The signing algorithm to be used. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setAS2SigningAlgorithm (@Nullable final ECryptoAlgorithmSign eSigningAlgo)
  {
    m_eSigningAlgo = eSigningAlgo;
    return this;
  }

  /**
   * Set the abstract format for AS2 message IDs. By default
   * {@link #DEFAULT_AS2_MESSAGE_ID_FORMAT} is used so there is no need to
   * change it. The replacement of placeholders depends on the underlying AS2
   * library.
   *
   * @param sMessageIDFormat
   *        The message ID format to be used. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setAS2MessageIDFormat (@Nullable final String sMessageIDFormat)
  {
    m_sMessageIDFormat = sMessageIDFormat;
    return this;
  }

  /**
   * Set the resource that represents the main business document to be
   * transmitted. It must be an XML document - other documents are not supported
   * by PEPPOL. This should NOT be the SBDH as this is added internally.
   *
   * @param aBusinessDocument
   *        The file containing the business document to be set. May not be
   *        <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setBusinessDocument (@Nonnull final File aBusinessDocument)
  {
    return setBusinessDocument (new FileSystemResource (aBusinessDocument));
  }

  /**
   * Set the resource that represents the main business document to be
   * transmitted. It must be an XML document - other documents are not supported
   * by PEPPOL. This should NOT be the SBDH as this is added internally.
   *
   * @param aBusinessDocument
   *        The byte array content of the business document to be set. May not
   *        be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setBusinessDocument (@Nonnull final byte [] aBusinessDocument)
  {
    return setBusinessDocument (new ReadableResourceByteArray (aBusinessDocument));
  }

  /**
   * Set the resource that represents the main business document to be
   * transmitted. It must be an XML document - other documents are not supported
   * by PEPPOL. This should NOT be the SBDH as this is added internally.
   *
   * @param aBusinessDocument
   *        The resource pointing to the business document to be set. May not be
   *        <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setBusinessDocument (@Nullable final IReadableResource aBusinessDocument)
  {
    m_aBusinessDocument = aBusinessDocument;
    return this;
  }

  /**
   * Set the PEPPOL sender ID. This is your PEPPOL participant ID.
   *
   * @param aPeppolSenderID
   *        The sender PEPPOL participant ID. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setPeppolSenderID (@Nullable final IParticipantIdentifier aPeppolSenderID)
  {
    m_aPeppolSenderID = aPeppolSenderID;
    return this;
  }

  /**
   * Set the PEPPOL receiver ID. This is the PEPPOL participant ID of the
   * recipient.
   *
   * @param aPeppolReceiverID
   *        The receiver PEPPOL participant ID. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setPeppolReceiverID (@Nullable final IParticipantIdentifier aPeppolReceiverID)
  {
    m_aPeppolReceiverID = aPeppolReceiverID;
    return this;
  }

  /**
   * Set the PEPPOL document type identifier for the exchanged business
   * document.
   *
   * @param aPeppolDocumentTypeID
   *        The PEPPOL document type identifier. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setPeppolDocumentTypeID (@Nullable final IDocumentTypeIdentifier aPeppolDocumentTypeID)
  {
    m_aPeppolDocumentTypeID = aPeppolDocumentTypeID;
    return this;
  }

  /**
   * Set the PEPPOL process identifier for the exchanged business document.
   *
   * @param aPeppolProcessID
   *        The PEPPOL process identifier. May not be <code>null</code>.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setPeppolProcessID (@Nullable final IProcessIdentifier aPeppolProcessID)
  {
    m_aPeppolProcessID = aPeppolProcessID;
    return this;
  }

  /**
   * Set the SMP client to be used. The SMP client can help to automatically
   * determine the following fields:
   * <ul>
   * <li>Receiver AS2 endpoint URL - {@link #setReceiverAS2Url(String)}</li>
   * <li>Receiver certificate - {@link #setReceiverCertificate(X509Certificate)}
   * </li>
   * <li>Receiver AS2 ID - {@link #setReceiverAS2ID(String)}</li>
   * </ul>
   * so that you need to call this method only if you did not set these values
   * previously. If any of the values mentioned above is already set, it's value
   * is not touched!
   * <p>
   * As a prerequisite to performing an SMP lookup, at least the following
   * properties must be set:
   * <ul>
   * <li>The PEPPOL receiver participant ID -
   * {@link #setPeppolReceiverID(IParticipantIdentifier)}</li>
   * <li>The PEPPOL document type ID -
   * {@link #setPeppolDocumentTypeID(IDocumentTypeIdentifier)}</li>
   * <li>The PEPPOL process ID - {@link #setPeppolProcessID(IProcessIdentifier)}
   * </li>
   * </ul>
   *
   * @param aSMPClient
   *        The SMP client to be used. May be <code>null</code> to indicate no
   *        SMP lookup necessary.
   * @return this for chaining
   */
  @Nonnull
  public AS2ClientBuilder setSMPClient (@Nullable final SMPClientReadOnly aSMPClient)
  {
    m_aSMPClient = aSMPClient;
    return this;
  }

  /**
   * This method is responsible for performing the SMP client lookup if an SMP
   * client was specified via {@link #setSMPClient(SMPClientReadOnly)}. If any
   * of the prerequisites mentioned there is not fulfilled a warning is emitted
   * via the {@link #getMessageHandler()} and nothing happens. If all fields to
   * be determined by the SMP are already no SMP lookup is performed either. If
   * the SMP lookup fails, a warning is emitted and nothing happens.
   *
   * @throws AS2ClientBuilderException
   *         In case SMP client lookup triggers an unrecoverable error via the
   *         message handler
   */
  protected void performSMPClientLookup () throws AS2ClientBuilderException
  {
    if (m_aSMPClient != null)
    {
      // Check pre-requisites
      if (m_aPeppolReceiverID == null)
        getMessageHandler ().warn ("Cannot perform SMP lookup because the PEPPOL receiver ID is missing");
      else
        if (m_aPeppolDocumentTypeID == null)
          getMessageHandler ().warn ("Cannot perform SMP lookup because the PEPPOL document type ID is missing");
        else
          if (m_aPeppolProcessID == null)
            getMessageHandler ().warn ("Cannot perform SMP lookup because the PEPPOL process ID is missing");
          else
          {
            // All prerequisites are matched

            // Check if all fields to be determined are present, to avoid
            // unnecessary lookup calls.
            if (m_sReceiverAS2Url == null || m_aReceiverCert == null || m_sReceiverAS2ID == null)
            {
              // Perform the lookup.
              EndpointType aEndpoint = null;
              try
              {
                if (s_aLogger.isDebugEnabled ())
                  s_aLogger.debug ("Performing SMP lookup for receiver '" +
                                   IdentifierHelper.getIdentifierURIEncoded (m_aPeppolReceiverID) +
                                   "' on document type '" +
                                   IdentifierHelper.getIdentifierURIEncoded (m_aPeppolDocumentTypeID) +
                                   "' and process ID '" +
                                   IdentifierHelper.getIdentifierURIEncoded (m_aPeppolProcessID) +
                                   "' using transport profile for AS2");

                aEndpoint = m_aSMPClient.getEndpoint (m_aPeppolReceiverID,
                                                      m_aPeppolDocumentTypeID,
                                                      m_aPeppolProcessID,
                                                      ESMPTransportProfile.TRANSPORT_PROFILE_AS2);
              }
              catch (final SMPClientException ex)
              {
                if (s_aLogger.isDebugEnabled ())
                  s_aLogger.debug ("Error querying SMP", ex);
                // Fall through
              }

              // Interpret the result
              if (aEndpoint == null)
              {
                // No such SMP entry
                getMessageHandler ().warn ("Failed to perform SMP lookup for receiver '" +
                                           IdentifierHelper.getIdentifierURIEncoded (m_aPeppolReceiverID) +
                                           "' on document type '" +
                                           IdentifierHelper.getIdentifierURIEncoded (m_aPeppolDocumentTypeID) +
                                           "' and process ID '" +
                                           IdentifierHelper.getIdentifierURIEncoded (m_aPeppolProcessID) +
                                           "' using transport profile for AS2");
              }
              else
              {
                // Extract from SMP response
                if (m_sReceiverAS2Url == null)
                  m_sReceiverAS2Url = SMPClientReadOnly.getEndpointAddress (aEndpoint);
                if (m_aReceiverCert == null)
                  try
                  {
                    m_aReceiverCert = SMPClientReadOnly.getEndpointCertificate (aEndpoint);
                  }
                  catch (final CertificateException ex)
                  {
                    getMessageHandler ().error ("Failed to build X.509 certificate from SMP client response", ex);
                  }
                if (m_sReceiverAS2ID == null)
                  try
                  {
                    m_sReceiverAS2ID = AS2ClientHelper.getSubjectCommonName (m_aReceiverCert);
                  }
                  catch (final CertificateException ex)
                  {
                    getMessageHandler ().error ("Failed to get the Receiver AS ID from the provided certificate", ex);
                  }
              }
            }
            else
            {
              if (s_aLogger.isDebugEnabled ())
                s_aLogger.debug ("Not performing SMP lookup because all target fields are already set!");
            }
          }
    }
  }

  /**
   * Certain values can by convention be derived from other values. This happens
   * inside this method. There is no need to call this method manually, it is
   * called automatically before {@link #verifyContent()} is called.
   */
  @OverridingMethodsMustInvokeSuper
  protected void setDefaultDerivedValues ()
  {
    if (m_sReceiverAS2KeyAlias == null)
    {
      // No key alias is specified, so use the same as the receiver ID (which
      // may be null)
      m_sReceiverAS2KeyAlias = m_sReceiverAS2ID;
      if (s_aLogger.isDebugEnabled ())
        s_aLogger.debug ("The receiver AS2 key alias was defaulted to the AS2 receiver ID");
    }
  }

  /**
   * Verify the content of all contained fields so that all know issues are
   * captured before sending. This method is automatically called before the
   * message is send (see {@link #sendSynchronous()}). All verification warnings
   * and errors are handled via the message handler.
   *
   * @throws AS2ClientBuilderException
   *         In case the message handler throws an exception in case of an
   *         error.
   * @see #setMessageHandler(IAS2ClientBuilderMessageHandler)
   */
  public void verifyContent () throws AS2ClientBuilderException
  {
    if (m_aKeyStoreFile == null)
      m_aMessageHandler.error ("No AS2 key store is defined");
    else
    {
      if (!m_aKeyStoreFile.exists ())
        m_aMessageHandler.error ("The provided AS2 key store '" +
                                 m_aKeyStoreFile.getAbsolutePath () +
                                 "' does not exist.");
      else
        if (!m_aKeyStoreFile.isFile ())
          m_aMessageHandler.error ("The provided AS2 key store '" +
                                   m_aKeyStoreFile.getAbsolutePath () +
                                   "' is not a file but potentially a directory.");
    }
    if (m_sKeyStorePassword == null)
      m_aMessageHandler.error ("No key store password provided. If you need an empty password, please provide an empty String!");

    if (StringHelper.hasNoText (m_sAS2Subject))
      m_aMessageHandler.error ("The AS2 message subject is missing");

    if (StringHelper.hasNoText (m_sSenderAS2ID))
      m_aMessageHandler.error ("The AS2 sender ID is missing");
    else
      if (!m_sSenderAS2ID.startsWith ("APP_"))
        m_aMessageHandler.warn ("The AS2 sender ID '" +
                                m_sSenderAS2ID +
                                "' should start with 'APP_' as required by the PEPPOL specification");

    if (StringHelper.hasNoText (m_sSenderAS2Email))
      m_aMessageHandler.error ("The AS2 sender email address is missing");
    else
      if (!EmailAddressHelper.isValid (m_sSenderAS2Email))
        m_aMessageHandler.warn ("The AS2 sender email address '" +
                                m_sSenderAS2Email +
                                "' seems to be an invalid email address.");

    if (StringHelper.hasNoText (m_sSenderAS2KeyAlias))
      m_aMessageHandler.error ("The AS2 sender key alias is missing");
    else
      if (!m_sSenderAS2KeyAlias.startsWith ("APP_"))
        m_aMessageHandler.warn ("The AS2 sender key alias '" +
                                m_sSenderAS2KeyAlias +
                                "' should start with 'APP_' for the use with the dynamic AS2 partnerships");
      else
        if (m_sSenderAS2ID != null && !m_sSenderAS2ID.equals (m_sSenderAS2KeyAlias))
          m_aMessageHandler.warn ("The AS2 sender key alias ('" +
                                  m_sSenderAS2KeyAlias +
                                  "') should match the AS2 sender ID ('" +
                                  m_sSenderAS2ID +
                                  "')");

    if (StringHelper.hasNoText (m_sReceiverAS2ID))
      m_aMessageHandler.error ("The AS2 receiver ID is missing");
    else
      if (!m_sReceiverAS2ID.startsWith ("APP_"))
        m_aMessageHandler.warn ("The AS2 receiver ID '" +
                                m_sReceiverAS2ID +
                                "' should start with 'APP_' as required by the PEPPOL specification");

    if (StringHelper.hasNoText (m_sReceiverAS2KeyAlias))
      m_aMessageHandler.error ("The AS2 receiver key alias is missing");
    else
      if (!m_sReceiverAS2KeyAlias.startsWith ("APP_"))
        m_aMessageHandler.warn ("The AS2 receiver key alias '" +
                                m_sReceiverAS2KeyAlias +
                                "' should start with 'APP_' for the use with the dynamic AS2 partnerships");
      else
        if (m_sReceiverAS2ID != null && !m_sReceiverAS2ID.equals (m_sReceiverAS2KeyAlias))
          m_aMessageHandler.warn ("The AS2 receiver key alias ('" +
                                  m_sReceiverAS2KeyAlias +
                                  "') should match the AS2 receiver ID ('" +
                                  m_sReceiverAS2ID +
                                  "')");

    if (StringHelper.hasNoText (m_sReceiverAS2Url))
      m_aMessageHandler.error ("The AS2 receiver URL (AS2 endpoint URL) is missing");
    else
      if (URLHelper.getAsURL (m_sReceiverAS2Url) == null)
        m_aMessageHandler.warn ("The provided AS2 receiver URL '" + m_sReceiverAS2Url + "' seems to be an invalid URL");

    if (m_aReceiverCert == null)
      m_aMessageHandler.error ("The receiver X.509 certificate is missing. Usually this is extracted from the SMP response");

    if (m_eSigningAlgo == null)
      m_aMessageHandler.error ("The signing algorithm for the AS2 message is missing");

    if (StringHelper.hasNoText (m_sMessageIDFormat))
      m_aMessageHandler.error ("The AS2 message ID format is missing.");

    if (m_aBusinessDocument == null)
      m_aMessageHandler.error ("The XML business document to be send is missing.");
    else
      if (!m_aBusinessDocument.exists ())
        m_aMessageHandler.error ("The XML business document to be send '" +
                                 m_aBusinessDocument.getPath () +
                                 "' does not exist.");

    if (m_aPeppolSenderID == null)
      m_aMessageHandler.error ("The PEPPOL sender participant ID is missing");
    else
      if (!IdentifierHelper.hasDefaultParticipantIdentifierScheme (m_aPeppolSenderID))
        m_aMessageHandler.warn ("The PEPPOL sender participant ID '" +
                                IdentifierHelper.getIdentifierURIEncoded (m_aPeppolSenderID) +
                                "' is using a non-standard scheme!");

    if (m_aPeppolReceiverID == null)
      m_aMessageHandler.error ("The PEPPOL receiver participant ID is missing");
    else
      if (!IdentifierHelper.hasDefaultParticipantIdentifierScheme (m_aPeppolReceiverID))
        m_aMessageHandler.warn ("The PEPPOL receiver participant ID '" +
                                IdentifierHelper.getIdentifierURIEncoded (m_aPeppolReceiverID) +
                                "' is using a non-standard scheme!");

    if (m_aPeppolDocumentTypeID == null)
      m_aMessageHandler.error ("The PEPPOL document type ID is missing");
    else
      if (!IdentifierHelper.hasDefaultDocumentTypeIdentifierScheme (m_aPeppolDocumentTypeID))
        m_aMessageHandler.warn ("The PEPPOL document type ID '" +
                                IdentifierHelper.getIdentifierURIEncoded (m_aPeppolDocumentTypeID) +
                                "' is using a non-standard scheme!");

    if (m_aPeppolProcessID == null)
      m_aMessageHandler.error ("The PEPPOL process ID is missing");
    else
      if (!IdentifierHelper.hasDefaultProcessIdentifierScheme (m_aPeppolProcessID))
        m_aMessageHandler.warn ("The PEPPOL process ID '" +
                                IdentifierHelper.getIdentifierURIEncoded (m_aPeppolProcessID) +
                                "' is using a non-standard scheme!");
  }

  /**
   * This is the main sending routine. It performs the following steps:
   * <ol>
   * <li>Verify that all required parameters are present and valid -
   * {@link #verifyContent()}</li>
   * <li>The business document is read as XML. In case of an error, an exception
   * is thrown.</li>
   * <li>The Standard Business Document (SBD) is created, all PEPPOL required
   * fields are set and the business document is embedded.</li>
   * <li>The SBD is serialized and send via AS2</li>
   * <li>The AS2 response incl. the MDN is returned for further evaluation.</li>
   * </ol>
   *
   * @return The AS2 response returned by the AS2 sender. This is never
   *         <code>null</code>.
   * @throws AS2ClientBuilderException
   *         In case the the business document is invalid XML or in case
   *         {@link #verifyContent()} throws an exception because of invalid or
   *         incomplete settings.
   */
  @Nonnull
  public AS2ClientResponse sendSynchronous () throws AS2ClientBuilderException
  {
    // Perform SMP client lookup
    performSMPClientLookup ();

    // Set derivable values
    setDefaultDerivedValues ();

    // Verify the whole data set
    verifyContent ();

    // Build message

    // 1. read XML
    Document aXMLDocument = null;
    try
    {
      aXMLDocument = DOMReader.readXMLDOM (m_aBusinessDocument);
    }
    catch (final SAXException ex)
    {
      throw new AS2ClientBuilderException ("Failed to read business document '" +
                                           m_aBusinessDocument.getPath () +
                                           "' as XML",
                                           ex);
    }

    // 2. build SBD data
    final PeppolSBDHDocument aDD = PeppolSBDHDocument.create (aXMLDocument.getDocumentElement ());
    aDD.setSenderWithDefaultScheme (m_aPeppolSenderID.getValue ());
    aDD.setReceiver (m_aPeppolReceiverID.getScheme (), m_aPeppolReceiverID.getValue ());
    aDD.setDocumentType (m_aPeppolDocumentTypeID.getScheme (), m_aPeppolDocumentTypeID.getValue ());
    aDD.setProcess (m_aPeppolProcessID.getScheme (), m_aPeppolProcessID.getValue ());

    // 3. build SBD
    final StandardBusinessDocument aSBD = new PeppolSBDHDocumentWriter ().createStandardBusinessDocument (aDD);
    final NonBlockingByteArrayOutputStream aBAOS = new NonBlockingByteArrayOutputStream ();
    if (new SBDMarshaller ().write (aSBD, new StreamResult (aBAOS)).isFailure ())
      throw new AS2ClientBuilderException ("Failed to serialize SBD!");
    aBAOS.close ();

    // 4. send message
    // Start building the AS2 client settings
    final AS2ClientSettings aAS2ClientSettings = new AS2ClientSettings ();
    // Key store
    aAS2ClientSettings.setKeyStore (m_aKeyStoreFile, m_sKeyStorePassword);
    // Fixed sender
    aAS2ClientSettings.setSenderData (m_sSenderAS2ID, m_sSenderAS2Email, m_sSenderAS2KeyAlias);

    // Dynamic receiver
    aAS2ClientSettings.setReceiverData (m_sReceiverAS2ID, m_sReceiverAS2KeyAlias, m_sReceiverAS2Url);
    aAS2ClientSettings.setReceiverCertificate (m_aReceiverCert);

    // AS2 stuff - no need to change anything in this block
    aAS2ClientSettings.setPartnershipName (aAS2ClientSettings.getSenderAS2ID () +
                                           "-" +
                                           aAS2ClientSettings.getReceiverAS2ID ());
    aAS2ClientSettings.setMDNOptions (new DispositionOptions ().setMICAlg (m_eSigningAlgo)
                                                               .setMICAlgImportance (DispositionOptions.IMPORTANCE_REQUIRED)
                                                               .setProtocol (DispositionOptions.PROTOCOL_PKCS7_SIGNATURE)
                                                               .setProtocolImportance (DispositionOptions.IMPORTANCE_REQUIRED));
    aAS2ClientSettings.setEncryptAndSign (null, m_eSigningAlgo);
    aAS2ClientSettings.setMessageIDFormat (m_sMessageIDFormat);

    final AS2ClientRequest aRequest = new AS2ClientRequest (m_sAS2Subject);
    aRequest.setData (aBAOS.toByteArray ());
    final AS2ClientResponse aResponse = new AS2Client ().sendSynchronous (aAS2ClientSettings, aRequest);
    return aResponse;
  }
}