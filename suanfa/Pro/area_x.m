function[Uw,Us]=area_x(Uw,Us,dt)

    global dx z g db

    [n,m,k]=size(Uw);


    Q=zeros(n,m,k+1);Q(:,:,1:3)=Us;Q(:,:,4)=z+Us(:,:,1);

    [Q_1,~,Q_n1,~]=x_boundary(Q);

    Q_alef=[Q_1;Q(1:n-1,:,:)];
    Q_arig=Q;
    Q_blef=Q;
    Q_brig=[Q(2:n,:,:);Q_n1];

    h_alef=Q_alef(:,:,1);h_arig=Q_arig(:,:,1);
    qu_alef=Q_alef(:,:,2);qu_arig=Q_arig(:,:,2);
    qv_alef=Q_alef(:,:,3);qv_arig=Q_arig(:,:,3);
    ne_alef=Q_alef(:,:,4);ne_arig=Q_arig(:,:,4);
    u_alef=qu_alef./h_alef;u_arig=qu_arig./h_arig;
    v_alef=qv_alef./h_alef;v_arig=qv_arig./h_arig;
    I=h_alef<=db;u_alef(I)=0;v_alef(I)=0;
    I=h_arig<=db;u_arig(I)=0;v_arig(I)=0;
    z_alef=Q_alef(:,:,4)-h_alef;z_arig=Q_arig(:,:,4)-h_arig;
    z_L=max(z_alef,z_arig);
    h_alefc=max(0,ne_alef-z_L);h_arigc=max(0,ne_arig-z_L);
    Fa=x_HLLC(h_alefc,u_alef,v_alef,h_arigc,u_arig,v_arig);

    h_blef=Q_blef(:,:,1);h_brig=Q_brig(:,:,1);
    qu_blef=Q_blef(:,:,2);qu_brig=Q_brig(:,:,2);
    qv_blef=Q_blef(:,:,3);qv_brig=Q_brig(:,:,3);
    ne_blef=Q_blef(:,:,4);ne_brig=Q_brig(:,:,4);
    u_blef=qu_blef./h_blef;u_brig=qu_brig./h_brig;
    v_blef=qv_blef./h_blef;v_brig=qv_brig./h_brig;
    I=h_blef<=db;u_blef(I)=0;v_blef(I)=0;
    I=h_brig<=db;u_brig(I)=0;v_brig(I)=0;
    z_blef=Q_blef(:,:,4)-h_blef;z_brig=Q_brig(:,:,4)-h_brig;
    z_R=max(z_blef,z_brig);
    h_blefc=max(0,ne_blef-z_R);h_brigc=max(0,ne_brig-z_R);
    Fb=x_HLLC(h_blefc,u_blef,v_blef,h_brigc,u_brig,v_brig);

    A=0.*Fa;B=0.*Fb;
    A(:,:,2)=1/2.*g.*(h_arig.^2-h_arigc.^2);
    B(:,:,2)=1/2.*g.*(h_blef.^2-h_blefc.^2);

    Us=Us-dt/dx.*(Fb-Fa+B-A);



    Q=zeros(n,m,k+1);Q(:,:,1:3)=Uw;Q(:,:,4)=z+Us(:,:,1)+Uw(:,:,1);

    [Q_1,~,Q_n1,~]=x_boundary(Q);

    Q_alef=[Q_1;Q(1:n-1,:,:)];
    Q_arig=Q;
    Q_blef=Q;
    Q_brig=[Q(2:n,:,:);Q_n1];

    h_alef=Q_alef(:,:,1);h_arig=Q_arig(:,:,1);
    qu_alef=Q_alef(:,:,2);qu_arig=Q_arig(:,:,2);
    qv_alef=Q_alef(:,:,3);qv_arig=Q_arig(:,:,3);
    ne_alef=Q_alef(:,:,4);ne_arig=Q_arig(:,:,4);
    u_alef=qu_alef./h_alef;u_arig=qu_arig./h_arig;
    v_alef=qv_alef./h_alef;v_arig=qv_arig./h_arig;
    I=h_alef<=db;u_alef(I)=0;v_alef(I)=0;
    I=h_arig<=db;u_arig(I)=0;v_arig(I)=0;
    z_alef=Q_alef(:,:,4)-h_alef;z_arig=Q_arig(:,:,4)-h_arig;
    z_L=max(z_alef,z_arig);
    h_alefc=max(0,ne_alef-z_L);h_arigc=max(0,ne_arig-z_L);
    Fa=x_HLLC(h_alefc,u_alef,v_alef,h_arigc,u_arig,v_arig);

    h_blef=Q_blef(:,:,1);h_brig=Q_brig(:,:,1);
    qu_blef=Q_blef(:,:,2);qu_brig=Q_brig(:,:,2);
    qv_blef=Q_blef(:,:,3);qv_brig=Q_brig(:,:,3);
    ne_blef=Q_blef(:,:,4);ne_brig=Q_brig(:,:,4);
    u_blef=qu_blef./h_blef;u_brig=qu_brig./h_brig;
    v_blef=qv_blef./h_blef;v_brig=qv_brig./h_brig;
    I=h_blef<=db;u_blef(I)=0;v_blef(I)=0;
    I=h_brig<=db;u_brig(I)=0;v_brig(I)=0;
    z_blef=Q_blef(:,:,4)-h_blef;z_brig=Q_brig(:,:,4)-h_brig;
    z_R=max(z_blef,z_brig);
    h_blefc=max(0,ne_blef-z_R);h_brigc=max(0,ne_brig-z_R);
    Fb=x_HLLC(h_blefc,u_blef,v_blef,h_brigc,u_brig,v_brig);

    A=0.*Fa;B=0.*Fb;
    A(:,:,2)=1/2.*g.*(h_arig.^2-h_arigc.^2);
    B(:,:,2)=1/2.*g.*(h_blef.^2-h_blefc.^2);

    Uw=Uw-dt/dx.*(Fb-Fa+B-A);
